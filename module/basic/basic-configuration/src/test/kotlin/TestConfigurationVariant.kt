import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import taboolib.module.configuration.Configuration
import taboolib.module.configuration.Type
import taboolib.module.configuration.util.expandVariants

/**
 * 配置变体：验证 `$variants` 能通过点路径 Patch 生成普通配置节点。
 *
 * @author sky
 */
class TestConfigurationVariant {

    @Test
    fun testExpandPatchVariant() {
        val conf = Configuration.loadFromString(
            """
            potion:
              name: Normal Potion
              item-extra:
                - type: standard
                  title: Heal@+1%
                - type: duration
                  time: 8s
              meta:
                legacy: true
              event:
                on_consume:
                  - ==: ITEM_DAMAGE
                    amount: 1
                  - ==: BUFF
                    buff:
                      - id: HEALING_POTION
                        data: 1.0
              ${'$'}variants:
                "{id}_low":
                  name: Low Potion
                  item-extra[0].title: Heal@+0.67%
                  event.on_consume[1].buff[0].data: 0.67
                  event.on_consume[2]:
                    ==: MESSAGE
                    text: Low Potion consumed
                  meta:
                    unique: true
                    rarity: low
            """.trimIndent(),
            Type.YAML,
        )

        conf.expandVariants()

        val itemExtra = conf.getMapList("potion_low.item-extra")
        val onConsume = conf.getMapList("potion_low.event.on_consume")
        val buff = (onConsume[1]["buff"] as List<*>).filterIsInstance<Map<*, *>>()
        val meta = conf.getConfigurationSection("potion_low.meta")!!

        assertEquals("Normal Potion", conf.getString("potion.name"))
        assertEquals("Low Potion", conf.getString("potion_low.name"))
        assertEquals("Heal@+0.67%", itemExtra[0]["title"])
        assertEquals(0.67, buff[0]["data"])
        assertEquals("MESSAGE", onConsume[2]["=="])
        assertEquals("Low Potion consumed", onConsume[2]["text"])
        assertEquals(true, meta.get("unique"))
        assertEquals("low", meta.getString("rarity"))
        assertFalse(meta.contains("legacy"))
        assertFalse(conf.contains("potion.${'$'}variants"))
        assertFalse(conf.contains("potion_low.${'$'}variants"))
    }

    @Test
    fun testExpandPatchVariantWithCustomKey() {
        val conf = Configuration.loadFromString(
            """
            potion:
              name: Normal Potion
              variants:
                "{id}_high":
                  name: High Potion
            """.trimIndent(),
            Type.YAML,
        )

        conf.expandVariants("variants")

        assertEquals("Normal Potion", conf.getString("potion.name"))
        assertEquals("High Potion", conf.getString("potion_high.name"))
        assertFalse(conf.contains("potion.variants"))
        assertFalse(conf.contains("potion_high.variants"))
    }

    @Test
    fun testAppendAndInsertListItem() {
        val conf = Configuration.loadFromString(
            """
            potion:
              item-extra:
                - title: Original First
                - title: Original Second
              ${'$'}variants:
                "{id}_mixed":
                  "item-extra[]+":
                    title: Appended
                  "item-extra[0]+":
                    title: Inserted First
            """.trimIndent(),
            Type.YAML,
        )

        conf.expandVariants()

        val itemExtra = conf.getMapList("potion_mixed.item-extra")

        assertEquals("Inserted First", itemExtra[0]["title"])
        assertEquals("Original First", itemExtra[1]["title"])
        assertEquals("Original Second", itemExtra[2]["title"])
        assertEquals("Appended", itemExtra[3]["title"])
    }

    @Test
    fun testOverrideListItem() {
        val conf = Configuration.loadFromString(
            """
            potion:
              item-extra:
                - title: Original First
                - title: Original Second
              ${'$'}variants:
                "{id}_bracket_override":
                  "item-extra[]":
                    - title: Bracket Override First
                    - title: Bracket Override Second
                "{id}_plain_override":
                  item-extra:
                    - title: Plain Override First
                    - title: Plain Override Second
            """.trimIndent(),
            Type.YAML,
        )

        conf.expandVariants()

        val bracketItemExtra = conf.getMapList("potion_bracket_override.item-extra")
        val plainItemExtra = conf.getMapList("potion_plain_override.item-extra")

        assertEquals(2, bracketItemExtra.size)
        assertEquals("Bracket Override First", bracketItemExtra[0]["title"])
        assertEquals("Bracket Override Second", bracketItemExtra[1]["title"])
        assertEquals(2, plainItemExtra.size)
        assertEquals("Plain Override First", plainItemExtra[0]["title"])
        assertEquals("Plain Override Second", plainItemExtra[1]["title"])
    }
}
