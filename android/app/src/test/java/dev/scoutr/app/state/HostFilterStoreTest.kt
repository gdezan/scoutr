package dev.scoutr.app.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostFilterStoreTest {
    @Test
    fun starts_on_all_hosts_on_every_instance() {
        assertNull(HostFilterStore().selected)
    }

    @Test
    fun select_keeps_one_host_until_changed() {
        val store = HostFilterStore()
        store.select("host-a")
        assertEquals("host-a", store.selected)
        store.select("host-b")
        assertEquals("host-b", store.selected)
    }

    @Test
    fun blank_selection_means_all_hosts() {
        val store = HostFilterStore()
        store.select("  ")
        assertNull(store.selected)
    }

    @Test
    fun reset_only_when_that_host_is_the_current_selection() {
        val store = HostFilterStore()
        store.select("host-a")

        store.resetIfSelected("host-b")
        assertEquals("host-a", store.selected)

        store.resetIfSelected("host-a")
        assertNull(store.selected)
    }

    @Test
    fun reset_on_all_is_a_no_op() {
        val store = HostFilterStore()
        store.resetIfSelected("host-a")
        assertNull(store.selected)
    }
}
