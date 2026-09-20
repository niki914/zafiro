package com.niki914.zafiro.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private interface SampleService {
    fun value(): String
}

private class SampleServiceImpl : SampleService {
    override fun value(): String = "impl"
}

private class OtherServiceImpl : SampleService {
    override fun value(): String = "other"
}

class ServiceRegistryTest {

    @After
    fun tearDown() {
        ServiceRegistry.clearForTest()
    }

    @Test
    fun requireService_returnsImplementationRegisteredByInterfaceType() {
        installService<SampleService>(SampleServiceImpl())
        assertEquals("impl", requireService<SampleService>().value())
    }

    @Test
    fun installService_secondRegistrationOverwritesFirst() {
        installService<SampleService>(SampleServiceImpl())
        installService<SampleService>(OtherServiceImpl())
        assertEquals("other", requireService<SampleService>().value())
    }

    @Test(expected = IllegalStateException::class)
    fun requireService_throwsWhenNotInstalled() {
        requireService<SampleService>()
    }

    @Test
    fun clearForTest_removesInstalledServices() {
        installService<SampleService>(SampleServiceImpl())
        ServiceRegistry.clearForTest()
        assertNull(ServiceRegistry.find(SampleService::class))
    }
}
