package com.bnm.diagnosis

import com.bnm.diagnosis.api.models.Product
import com.bnm.diagnosis.api.models.resolveProductName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks in the multilingual product-name resolver that fixed the POS grid
 * showing raw `{"en":"..."}` instead of the product name.
 */
class ProductNameTest {
    @Test
    fun plainNamePassesThrough() {
        assertEquals("Masala Chips 100g", resolveProductName("Masala Chips 100g"))
    }

    @Test
    fun englishJsonResolves() {
        assertEquals("Cold Brew Coffee 250ml", resolveProductName("""{"en":"Cold Brew Coffee 250ml"}"""))
    }

    @Test
    fun englishPickedFromMultiLang() {
        assertEquals("Shirt", resolveProductName("""{"hi":"कमीज़","en":"Shirt"}"""))
    }

    @Test
    fun firstValueWhenNoEnglish() {
        assertEquals("कमीज़", resolveProductName("""{"hi":"कमीज़"}"""))
    }

    @Test
    fun productDisplayNameUsesResolver() {
        val p = Product(id = "1", name = """{"en":"Protein Energy Bar"}""", price = 60.0)
        assertEquals("Protein Energy Bar", p.displayName)
        // effectivePrice falls back to price when selling_price is null (the ₹0 fix path).
        assertEquals(60.0, p.effectivePrice)
    }

    @Test
    fun plainProductDisplayNameUnchanged() {
        val p = Product(id = "2", name = "Steel Water Bottle 750ml", price = 350.0)
        assertEquals("Steel Water Bottle 750ml", p.displayName)
        assertEquals(350.0, p.effectivePrice)
    }
}
