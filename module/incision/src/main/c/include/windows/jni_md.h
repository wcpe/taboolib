#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_

#ifndef JNIEXPORT
  #define JNIEXPORT __declspec(dllexport)
#endif
#define JNIIMPORT __declspec(dllimport)

/* Windows x64/arm64 只有一种调用约定，__stdcall 在这些目标上会被编译器忽略；
   这里保留 x86-32 的语义以对齐 JDK 的 win32/jni_md.h */
#if defined(_M_IX86) || defined(__i386__)
  #define JNICALL __stdcall
#else
  #define JNICALL
#endif

typedef long jint;
typedef __int64 jlong;
typedef signed char jbyte;

#endif
