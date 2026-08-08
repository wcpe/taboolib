# Incision 技术原理

本文档用于解释 Incision 模块的底层实现原理，面向需要深入理解或二次开发的读者。
用户向导请看 [README.md](README.md)。

---

## 目录

1. [一句话定性](#1-一句话定性)
2. [核心替换链路](#2-核心替换链路)
3. [IncisionBridge 桥模式](#3-incisionbridge-桥模式)
4. [三个后端](#4-三个后端)
5. [JvmtiBackend 深入剖析](#5-jvmtibackend-深入剖析)
6. [两种独立的"编译"](#6-两种独立的编译)
7. [FrameVerifier 安全网](#7-frameverifier-安全网)
8. [生命周期与织入时机](#8-生命周期与织入时机)
9. [与其他 AOP 技术对比](#9-与其他-aop-技术对比)
10. [限制与权衡](#10-限制与权衡)
11. [FAQ](#11-faq)

---

## 1. 一句话定性

**运行时字节码织入（Runtime Bytecode Weaving）**，不是动态代理，不修改类名。

| 问题 | 答案 |
|---|---|
| 是动态代理吗？ | 不是。JDK Proxy 会生成 `$Proxy0` 新类并且需要接口；Incision 不创建新类 |
| 会修改原本类的类名吗？ | **不会**。类名、`Class` 对象、ClassLoader、已有实例引用全部保留 |
| 那改了什么？ | 只改目标方法**方法体内部**的字节码，插入一条固定的 `INVOKESTATIC` 调用 |
| 怎么让修改生效？ | 通过 `java.lang.instrument.Instrumentation.retransformClasses()` 或 JVMTI 原生 `RetransformClasses` 对已加载的类做原地热替换 |

关键认知：**JVM 不允许"重新加载"已加载的类**，但允许**原地替换方法体**。Incision 利用的就是这个能力。

---

## 2. 核心替换链路

### 2.1 总体流程

```
┌───────────────────────────────────────────────────────────┐
│  用户声明：@Surgeon / Scalpel {} DSL                     │
└───────────────────────────────────────────────────────────┘
                             │ CONST 阶段
                             ▼
┌───────────────────────────────────────────────────────────┐
│  SurgeonScanner 扫描 → AdviceEntry                        │
│  TheatreDispatcher 注册 → target-signature 索引           │
└───────────────────────────────────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────┐
│  Scalpel 按 owner 聚合目标，触发 Backend.retransform     │
└───────────────────────────────────────────────────────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
        Instrumentation   JVMTI       ClassLoaderHook
              │              │              │
              └──────────────┼──────────────┘
                             ▼
┌───────────────────────────────────────────────────────────┐
│  transform 回调：原字节码 → Scalpel.weave → 新字节码      │
│  ├─ ClassNode + ASM Tree API                              │
│  ├─ AdviceAdapter 在 HEAD/TAIL/RETURN 插入 dispatcher 调用│
│  ├─ SiteWeaver 在 INVOKE/FIELD/NEW 等锚点插入             │
│  ├─ FrameVerifier 预检帧一致性，失败回退原字节码          │
│  └─ ClassWriter(COMPUTE_FRAMES) 写出                      │
└───────────────────────────────────────────────────────────┘
                             │
                             ▼
┌───────────────────────────────────────────────────────────┐
│  JVM 接受新字节码，原地替换方法体                         │
│  类名 / Class 对象 / 父类 / 接口 / 字段 / 方法签名全保留  │
│  已有实例继续有效，下次调用命中新字节码                   │
└───────────────────────────────────────────────────────────┘
```

### 2.2 插入的字节码形态

weaver 在目标方法体的关键位点插入**一条固定调用**：

```
INVOKESTATIC io/izzel/incision/bridge/IncisionBridge.dispatch
  (Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
```

参数含义：

| 参数 | 内容 |
|---|---|
| `String targetSignature` | 目标方法签名（编译期常量串，带 phase 后缀如 `@TRAIL_THROW`） |
| `Object self` | 实例方法的 `this`；静态方法为 `null` |
| `Object[] args` | 原方法的所有实参装箱后的数组 |
| 返回值 | advice 链决定的结果，`null` 表示"继续执行原方法" |

**字节码里不内联 handler 逻辑**——只塞一个桥调用。真正的 handler 方法注册在运行时的 `SurgeryRegistry` 里，由 `TheatreDispatcher` 按 signature 查 `AdviceChain` 执行。

这种"只写桥"的设计带来三个好处：

1. **运行时可 suspend/resume/heal**：字节码只织一次，启停靠 dispatcher 层旁路
2. **handler 可热插拔**：增删 advice 不需要重新 retransform
3. **诊断入口统一**：所有织入都经过同一个 dispatch 路径，便于监控和故障分析

---

## 3. IncisionBridge 桥模式

### 3.1 为什么需要这个桥

如果 weaver 直接把调用写成 `INVOKESTATIC taboolib/module/incision/runtime/TheatreDispatcher.dispatch`，会遇到一个致命问题：

**TabooLib Gradle 插件的 `RelocateRemapper` 在每个插件打包时会把 `taboolib.*` 重定向为 `<plugin.group>.taboolib.*`**。

结果是：
- 插件 A 里的 `TheatreDispatcher` 被重定向为 `com.a.taboolib.module.incision.runtime.TheatreDispatcher`
- 插件 B 里的 `TheatreDispatcher` 被重定向为 `com.b.taboolib.module.incision.runtime.TheatreDispatcher`
- 两者类名不同、ClassLoader 不同，**无法共享同一个 dispatcher**
- 插件 A 织入的方法里写死了插件 A 的 `TheatreDispatcher`，插件 B 无法拦截同一 target

### 3.2 桥的位置

桥必须放在**不被 relocate 的包名下**，且用 **Java 实现**避免 Kotlin 运行时依赖（`kotlin.*` 同样会被 relocate）。

```java
// module/incision/src/main/java/io/izzel/incision/bridge/IncisionBridge.java
package io.izzel.incision.bridge;

public final class IncisionBridge {
    public static Object dispatch(String targetSignature, Object self, Object[] args) {
        // 1. 优先走系统 ClassLoader 上的 IncisionGateHost（多插件共享宿主）
        // 2. 退化为当前调用方 ClassLoader 的本地 TheatreDispatcher（单插件场景）
    }
}
```

`io.izzel.*` 在 relocate 名单之外，所以所有插件的织入字节码都指向**同一个**桥类。

### 3.3 双路径解析

```
织入字节码
   │
   ▼
 IncisionBridge.dispatch
   │
   ├─► 路径 A：系统 ClassLoader 上的 IncisionGateHost
   │   （由 GateBootstrapper 通过 appendToSystemClassLoaderSearch 推入）
   │   所有插件共享同一宿主，advice 跨插件可见
   │
   └─► 路径 B：ClassLoader-local 的 TheatreDispatcher
       （fallback，单插件场景或宿主绑定失败时）
       仅当前插件 ClassLoader 内部可见
```

路径 A 是正式路径，路径 B 是兜底。两条都通过反射穿透 ClassLoader 边界，避免类型一致性问题（不同 CL 的 `TheatreDispatcher` 虽然同名但不是同一个 `Class`）。

---

## 4. 三个后端

```kotlin
interface Backend {
    val name: String
    fun available(): Boolean
    fun addTransformer(className: String, transformer: (ByteArray) -> ByteArray?): BackendToken
    fun retransform(className: String): Boolean
}
```

三个实现各有定位：

| 后端 | 原理 | 已加载类 | 未加载类 | 生产环境可用性 |
|---|---|---|---|---|
| **InstrumentationBackend** | self-attach → `java.lang.instrument.Instrumentation.retransformClasses` | ✅ | ✅ | 受限（JDK 21+ 需开关，Paper 常禁） |
| **JvmtiBackend** | 预编译 native agent → JVMTI `RetransformClasses` | ✅ | ✅ | **最稳**（不依赖 attach 权限） |
| **ClassLoaderHookBackend** | 委派给 InstrumentationBackend，本身是占位 | ❌ | ✅ | 依附前两者 |

### 4.1 InstrumentationBackend

通过 self-attach 拿到 `Instrumentation` 句柄：

```kotlin
private fun resolve(): Instrumentation? {
    return ByteBuddyAttacher.tryInstall()   // 优先：需要 byte-buddy-agent
        ?: ManualSelfAttach.attach()        // 兜底：JDK 8 用 tools.jar，JDK 9+ 用 jdk.attach 模块
}
```

**self-attach 的固有阻碍**：

- JDK 9+：默认禁止当前进程 attach 自身；如需启用，必须在 JVM 启动参数中设置 `-Djdk.attach.allowAttachSelf=true`。Incision 不会在运行时修改这个进程级属性，因为 HotSpot 读取的是启动快照，而 ByteBuddy 等库读取实时属性，修改会造成策略分裂并影响其他插件
- JDK 21+：无 `-XX:+EnableDynamicAgentLoading` 时打印警告并最终禁止
- Paper/Spigot 生产配置：常以安全硬化名义禁用 self-attach
- 精简 JRE：可能缺 `tools.jar` 或 `jdk.attach` 模块

一旦任一条件未满足，Instrumentation 路径直接不可用。

### 4.2 JvmtiBackend

详见下一节。

### 4.3 ClassLoaderHookBackend

名字具有迷惑性——它并不真的 hook ClassLoader。实现里：

```kotlin
// 安装时直接把请求委派给 InstrumentationBackend
for ((cls, list) in transformers) {
    for (t in list) InstrumentationBackend.addTransformer(cls, t)
}
```

**实际作用**：保留一个抽象层便于未来引入真正的 ClassLoader 级拦截（比如对无法 retransform 的引导类），目前是 Instrumentation 的别名。

---

## 5. JvmtiBackend 深入剖析

### 5.1 存在的核心理由

绕开 self-attach 的封锁。

InstrumentationBackend 依赖 self-attach，而 self-attach 在真实服务端环境里经常堵死（见 4.1）。JvmtiBackend 把路径换成：

**不做 attach，直接 `System.load()` 加载一个自己写的 JVMTI native agent。**

`System.load()` 是 JNI 标准能力，几乎任何 JVM 都不会拦——比 attach 权限宽松得多。

### 5.2 物理组成

```
module/incision/src/main/c/
  ├─ incision_jvmti.c                ← 手写 C 源码
  ├─ build-all.bat                   ← 交叉编译脚本
  └─ include/                        ← JNI 头文件

module/incision/src/main/resources/native/
  ├─ windows/x64/incision-jvmti.dll
  ├─ windows/arm64/incision-jvmti.dll
  ├─ linux/x64/libincision-jvmti.so
  ├─ linux/arm64/libincision-jvmti.so
  ├─ macos/x64/libincision-jvmti.dylib
  └─ macos/arm64/libincision-jvmti.dylib
```

**预编译 6 个平台二进制**打进 jar，用户不需要装编译工具链。

### 5.3 自举流程

```kotlin
fun tryLoad(): Boolean {
    val lib = extractNativeLib() ?: return false
    // 关键：在 System.load 之前设置 property
    System.setProperty("incision.jvmti.class", JvmtiBackend::class.java.name)
    System.load(lib.absolutePath)
    return nInit(JvmtiBackend::class.java)
}
```

步骤：

1. 检测 `os.name` / `os.arch` → 选对应 .dll/.so/.dylib
2. 释放到 `java.io.tmpdir/incision-native/`（文件名带哈希，去重复用）
3. 设置 system property `incision.jvmti.class` 指向当前 JvmtiBackend 的**实际全限定名**
4. `System.load(lib)` → native 的 `JNI_OnLoad` 回调
5. native 从 property 读类名 → `FindClass` → `RegisterNatives` 绑定 JNI 方法
6. `nInit` 里 native 调 JVMTI `AddCapabilities` 获取 `can_retransform_classes` / `can_redefine_classes` / `can_generate_all_class_hook_events` 等能力
7. 挂 `ClassFileLoadHook` 事件，bytes 经过它时回调 Java 侧 `onClassFileLoad`

### 5.4 为什么用 system property 传类名

`TabooLib Gradle 插件` 的 `RelocateRemapper` 会把 `taboolib.module.incision.loader.JvmtiBackend` 重定向为 `<plugin.group>.taboolib.module.incision.loader.JvmtiBackend`。

native 代码里**不能写死 Java 类名**，否则重定位后找不到。用 property 动态传名字是这里的标准解法。

### 5.5 五项职责

JvmtiBackend 不只是"另一个 retransform 通道"，它提供了其他后端没有的独家能力：

#### 职责 1：retransform 主执行器

```kotlin
override fun retransform(className: String): Boolean {
    val cls = findLoadedClass(className) ?: return true  // 未加载 → 靠 ClassFileLoadHook 命中
    return nRetransform(cls)                              // 已加载 → JVMTI RetransformClasses
}
```

native 侧调 `jvmtiEnv->RetransformClasses(env, 1, &target)`。与 Instrumentation 走的是同一套 JVM 底层 API，只是不经过 `java.lang.instrument` 壳。

#### 职责 2：原始字节码缓存

```kotlin
// Scalpel.weave 首句
val sourceBytes = JvmtiBackend.getCachedOriginal(probeOwner) ?: run {
    JvmtiBackend.cacheOriginal(probeOwner, originalBytes)
    originalBytes
}
```

**为什么关键**：一个类被织入后，下次 retransform 时 JVM 喂过来的 bytes 是**已经被上次修改过的**。基于它再织会叠层。

native 侧缓存**首次见到的干净字节码**，任何重织都从原始状态出发，保证幂等。Instrumentation 根本没有这个能力。

#### 职责 3：原始字节码抽取 `nExtractClassBytes`

对**从未织入过**的已加载类，通过 `RetransformClasses` + ClassFileLoadHook 的特殊组合直接捞 bytes。用于诊断、签名校验、冲突分析等场景。

Instrumentation 无法做这件事——它只在 transform 回调里短暂看得见 bytes，回调结束就丢了。

#### 职责 4：JNI 级 `nDefineClass`

```kotlin
fun defineClassInClassLoader(loader: ClassLoader?, name: String, bytes: ByteArray): Class<*>?
```

JNI 的 `DefineClass` 可以往**任意 ClassLoader**（包括 bootstrap、ext、system）塞类，不需要那个 ClassLoader 配合：

- 不要求 ClassLoader 是 `URLClassLoader`
- 不要求反射调 `defineClass`（受 `accessible` 限制）
- 不要求 ClassLoader 有公开 API

用途：`BodiesClassGenerator` 生成的桥接类、`PredCompiler` 的谓词类——这些需要精准落户到某个 CL 的辅助类，走 JNI 最干净。

#### 职责 5：绕过访问控制的字段/方法访问器

```kotlin
external fun nFieldGet(obj, ownerClass, fieldName, fieldDesc): Any?
external fun nFieldSet(...)
external fun nStaticFieldGet(...)
external fun nStaticFieldSet(...)
external fun nInvokeMethod(...)
```

JNI 从 VM 底层操作字段和方法，**完全绕过** Java 的 `IllegalAccessException`、模块封装、`setAccessible` 警告。

- 不需要 `Field.setAccessible(true)`（JDK 17+ 会警告或拒绝）
- 不受模块边界约束（`Unsafe` 也做不到这一点的一部分）
- 不受 `Lookup` 的私有访问限制

这是 Reflex / `Unsafe` 都做不到或做得不干净的事。Scalpel 和 Surgeon 需要读写目标对象私有状态、调用 private/package-private 方法时不必到处喷 `setAccessible`。

**用户侧出口**：`IncisionAccessor`（`api/IncisionAccessor.kt`）是这些 native 方法的唯一用户层门面。它提供解析缓存（`(Class, fieldName) → ResolvedField`）和三级 fallback（JVMTI → 反射 → Unsafe）。handler 通过 Lambda 工厂（`api/Accessors.kt`）或 `Theatre` 接口的 default 方法间接调用 `IncisionAccessor`，不直接接触 `JvmtiBackend`。

### 5.6 在系统中的位置

```
┌──────────────────────────────────────────────────────────┐
│ 织入请求                                                 │
└──────────────────────────────────────────────────────────┘
                         │
                         ▼
             ┌──────────────────────────┐
             │ Backend 选择（按 available） │
             └──────────────────────────┘
                 │                   │
     ┌───────────▼──────────┐  ┌─────▼─────────────────┐
     │ InstrumentationBE    │  │ JvmtiBackend          │
     │ (self-attach)        │  │ (native agent)        │
     │                      │  │                       │
     │ • 开发机常用         │  │ • 生产主力            │
     │ • 有 attach 权限即可 │  │ • 无视 attach 管制    │
     │ • 仅 retransform     │  │ • + 原字节码缓存      │
     │                      │  │ • + 原始 bytes 抽取   │
     │                      │  │ • + 任意 CL defineClass│
     │                      │  │ • + 无访问控制字段/方法│
     └──────────────────────┘  └───────────────────────┘
```

### 5.7 一句话定位

**JvmtiBackend 是"无视管制、能力更强、带缓存"的 native 兜底主力——它让 Incision 在 Paper/JDK 21+ 生产服务器上真正能跑，同时提供其他后端根本没有的原字节码缓存、裸 defineClass、无访问控制的字段/方法访问这些独家能力。**

---

## 6. 两种独立的"编译"

Incision 内部有两条**完全不同**的字节码生产链路，初看容易混淆：

| | **核心织入（Scalpel / SiteWeaver）** | **PredCompiler** |
|---|---|---|
| 目标 | 改已存在的用户类（Bukkit / NMS / 插件自己的类） | 编译 `where "..."` 这种 DSL 字符串 |
| 产出 | 原类的修改版字节码 | 一个全新的 `Predicate` 实现类 |
| 类名 | **保持原名**（`org.bukkit.entity.Player` 还是 `Player`） | 生成新名（`.../pred/gen/Pred$0`, `$1`...） |
| 加载方式 | `retransformClasses` 原地替换 | `defineClass` 加载新类 |
| 什么时候发生 | CONST 阶段、insertion 时 | advice 注册时一次性 |
| 运行时开销 | 一次性写入，后续调用直接走新字节码 | `Predicate.test(ctx)` 像普通方法调用 |

两者都用 ASM，但机制和目的完全不同——**本体是改现有类，PredCompiler 是造新类**。

`where` 为什么要编译成类？因为谓词在运行时被高频调用（每次方法命中都评估），用反射或解释器会炸掉性能。编译成独立 `Predicate` 子类后，`dispatcher.pred.test(ctx)` 就是普通虚方法调用，JIT 能充分优化。

---

## 7. FrameVerifier 安全网

Tree API 改写 InsnList 后，JVM 加载时会跑一次字节码校验（Verifier）。任何 stack map frame 不一致都会抛 `VerifyError`，而且错误信息很难定位——直接 crash 服务器。

Incision 在写出新字节码**之前**先自己跑一遍 `Analyzer<BasicValue>` + `BasicVerifier`：

```kotlin
// Scalpel.applySiteWeaver
try {
    FrameVerifier.verify(classNode)
} catch (e: AnalyzerException) {
    Forensics.warn("帧验证失败，回退原字节码: $className.$methodName — ${e.message}")
    return bytes  // 返回织入前的字节码
}
```

**失败就回退**，不把坏 class 喂给 JVM。这是"开发期字节码 bug 导致服务器崩溃"这类事故的最后一道防线。

代价：每次织入多一次完整字节码分析，但只在 CONST 启动期，运行时零开销。

---

## 8. 生命周期与织入时机

Incision 把 `@Surgeon` 扫描和物理织入尽量前推：

```
NONE → CONST → INIT → LOAD → ENABLE → ACTIVE → DISABLE
         │
         └─► @Surgeon 扫描 + Scalpel 织入 发生在这里
```

**为什么是 CONST 而不是 ENABLE**：

- `@Awake(LifeCycle.ENABLE)` 里注册的 advice 来不及拦截 `@Awake(INIT/LOAD)` 里的代码
- `CONST` 是 TabooLib 给用户代码开放的最早窗口
- 更早的阶段（`ClassVisitorAwake` 之前的 TabooLib 内部引导）无法干预

**仍然不能拦截的**：

- 插件 main class 的静态初始化块（`<clinit>` 发生在类加载瞬间，比 CONST 更早）
- TabooLib 自身启动阶段的代码（织入器还没启动）
- bootstrap / system ClassLoader 上某些核心类（取决于 retransformable 能力）

对于"确实需要更早"的极端场景，用户需要通过 ClassFileTransformer 机制在 pre-main 阶段注册（Incision 不自动代劳）。

---

## 9. 与其他 AOP 技术对比

| 特性 | Incision | JDK Proxy | cglib | Mixin | AspectJ LTW |
|---|---|---|---|---|---|
| 改类名？ | ❌ | ✅ 生成 `$Proxy0` | ✅ 生成 `$$EnhancerByCGLIB` | ❌ | ❌ |
| 需接口？ | ❌ | ✅ | ❌ | ❌ | ❌ |
| 拦 final 方法？ | ✅ | ❌ | ❌（final 类/方法拦不了） | ✅ | ✅ |
| 拦已加载类？ | ✅ | ❌（必须通过代理对象调用） | ❌ | 需 launch wrapper | ✅ |
| 拦 NMS 内部 `this.foo()`？ | ✅ | ❌（自调用拦不住） | ❌ | ✅ | ✅ |
| 需编译期 agent？ | ❌ | ❌ | ❌ | ✅ 启动参数 | ✅ 启动参数 |
| 热插拔？ | ✅（dispatcher 层 suspend/resume） | ❌ | ❌ | ❌ | ❌ |
| 在 Paper 正式服跑？ | ✅（JVMTI 后端） | ✅ | ✅ | 看 launcher | 依赖 agent |
| 语法复杂度 | 中（注解 + DSL） | 低 | 低 | 中（Java mixin） | 高（AspectJ 语法） |

**核心差异化**：

- 对 JDK Proxy / cglib：Incision 解决的是"目标已经在那里、不走代理对象的调用路径"问题。NMS / Bukkit 的代码就是这种场景。
- 对 Mixin：Mixin 需要 launch wrapper（Minecraft 客户端场景常用），而 Incision 是纯运行时插件级，不改启动链路。
- 对 AspectJ LTW：AspectJ 功能最全但需要 `-javaagent` 参数。Incision 的 JVMTI 后端实现了"**无任何启动参数的 retransform**"。

---

## 10. 限制与权衡

### 10.1 技术限制（来自 JVM）

`retransformClasses` 和 JVMTI `RetransformClasses` 的约束：

| 项 | 能改 | 不能改 |
|---|---|---|
| 类名 | | ❌ |
| 父类 / 接口 | | ❌ |
| 方法签名（增删方法、改参数） | | ❌ |
| 字段（增删字段、改类型） | | ❌ |
| 方法体字节码 | ✅ | |
| 注解 | 部分 JVM 支持 | |

**推论**：Incision 不能做 Mixin 的 `@Shadow` 新字段、不能加新方法。只能改方法体——这也是为什么所有插入都以 `dispatch` 调用的形式出现。

### 10.2 性能特征

- **启动期**：每个织入目标多一次 retransform + ASM 解析 + FrameVerifier 分析
- **运行期**：每次命中目标方法多一次 `IncisionBridge.dispatch` → 反射 invoke → 查表 → 执行 handler
- **反射开销**：跨 ClassLoader 的桥调用用反射，但热点会被 JIT inline；实测开销在微秒量级

不建议在每秒百万调用的热路径上织入。适合事件入口、网络包、命令处理这类"逻辑决策点"。

### 10.3 跨 ClassLoader 复杂性

Minecraft 插件体系的 ClassLoader 拓扑本就复杂（PluginClassLoader / IsolatedClassLoader / AppClassLoader / Bootstrap），再叠加 TabooLib 的包重定位，使得"同一个类名"在不同 CL 下是**不同的 Class 对象**。

Incision 的应对：
- 桥放在不被重定位的 `io.izzel.*` 包
- 跨 CL 调用全用反射，不假设类型可达
- `IncisionGateHost` 放在系统 CL，成为所有插件共享的单点

这套机制能跑但不优雅——**根本上说，Minecraft 插件生态的 CL 设计不是为 AOP 友好的**。

### 10.4 诊断难度

字节码层 bug 的错误信息通常是 `VerifyError` 或 `ClassFormatError`，对用户不友好。Incision 的对策：

- `Forensics` 模块把 warn/error 结构化
- `Trauma` 异常类型把失败分类
- `FrameVerifier` 提前拦截坏字节码
- `incision.dev=true` 时把织入前后的 class 文件 dump 到磁盘，便于人工比对

---

## 11. FAQ

### Q: Incision 会修改原本类的类名吗？

不会。类名、Class 对象、父类、接口、字段表、方法表（签名）全部保留。只有方法体内部的字节码被修改。

### Q: 是动态代理吗？

不是。动态代理会生成新类，而且需要接口、只能拦通过代理对象的调用。Incision 不创建新类，拦的是原类本身，自调用 / final / NMS 内部调用都能拦。

### Q: 那是怎么做到"让原类变成新行为"的？

JVM 的 `Instrumentation.retransformClasses` / JVMTI `RetransformClasses` 允许**原地替换已加载类的方法字节码**。Incision 把 ASM 生成的新字节码交给 JVM，JVM 内部把对应的方法体换掉。Class 对象身份不变，旧实例继续有效，下次调用走新字节码。

### Q: 为什么需要 IncisionBridge 这个 Java 类？

因为 TabooLib Gradle 插件会重定位 `taboolib.*` 包名。如果织入字节码里写死 `taboolib.module.incision.runtime.TheatreDispatcher`，每个插件打包后这个类的全限定名都不同，无法共享同一个 dispatcher。桥放在不被重定位的 `io.izzel.*` 包，所有插件指向同一个桥。

### Q: JvmtiBackend 和 InstrumentationBackend 有什么区别？

两者最终都调 JVM 同一个底层 API（RetransformClasses），但拿到这个 API 的**路径**不同：

- Instrumentation：通过 `java.lang.instrument` → self-attach → Agent。受 JVM 的 attach 权限管制。
- JVMTI：直接 `System.load()` 装载自带的 native agent，绕过 attach 机制。

此外 JVMTI 后端还多了原字节码缓存、任意 CL 的 `defineClass`、无访问控制的字段/方法访问等独家能力。

### Q: 那为什么不直接只用 JvmtiBackend？

需要预编译 6 个平台二进制，jar 体积会变大（native 库总共几百 KB）。Instrumentation 纯 Java 实现，更轻量。在开发机和允许 self-attach 的环境下 Instrumentation 够用。JvmtiBackend 是生产环境兜底和能力扩展。

### Q: 能在 `@Awake(ENABLE)` 里声明 advice 吗？

能，但效果等同 ENABLE 之后才织入——ENABLE 之前已经执行过的代码不会被拦截。推荐改成 `@Surgeon` 注解模式，扫描发生在 CONST 阶段，能覆盖 INIT/LOAD/ENABLE 阶段的宿主代码。

### Q: 会和其他字节码框架（Mixin / AspectJ）冲突吗？

不会必然冲突。所有字节码框架都基于 ASM 和 JVM retransform，机制兼容。但如果两个框架织同一个方法的同一个位点，字节码层的叠加顺序取决于 transformer 注册顺序，可能产生意外结果。建议隔离职责域。

### Q: 运行时能"卸载"某个 advice 吗？

能，通过 `Suture.heal()` 或 `close()`。但"卸载"不是回滚字节码（字节码里的 dispatch 调用还在），而是让 dispatcher 在那个 target 上不再有 handler 可执行——调用进来 bridge 后直接返回 null，控制权回到原方法体。

### Q: 为什么 `suspend` 不直接回滚字节码？

回滚字节码意味着再做一次 retransform，有成本而且非原子。dispatcher 层的 suspend 只是改一个标志位，纳秒级开销。对"临时停掉"这种高频操作，dispatcher 旁路比字节码回滚合算得多。

---

## 附：关键文件索引

| 路径 | 作用 |
|---|---|
| `weaver/Scalpel.kt` | 主 weaver，对单个类做注入 |
| `weaver/SiteWeaver.kt` | Tree API 路径的方法级织入驱动 |
| `weaver/FrameVerifier.kt` | 字节码 verify 预检安全网 |
| `loader/Backend.kt` | 后端接口 |
| `loader/InstrumentationBackend.kt` | self-attach 路径 |
| `loader/JvmtiBackend.kt` | native JVMTI 路径 |
| `loader/ClassLoaderHookBackend.kt` | 占位，委派给 Instrumentation |
| `bridge/IncisionBridge.java` | 不被重定位的 Java 桥 |
| `runtime/TheatreDispatcher.kt` | advice 链调度中心 |
| `pred/PredCompiler.kt` | `where` DSL 的独立字节码编译器 |
| `api/IncisionAccessor.kt` | 字段/方法访问底层门面（解析缓存 + JVMTI/反射/Unsafe 三级 fallback） |
| `api/Accessors.kt` | Lambda 工厂 + Accessor 类族（handler 字段/方法访问的主推 API） |
| `src/main/c/incision_jvmti.c` | JVMTI native agent 源码 |
