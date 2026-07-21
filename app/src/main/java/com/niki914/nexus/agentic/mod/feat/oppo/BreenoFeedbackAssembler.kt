package com.niki914.nexus.agentic.mod.feat.oppo

import com.niki914.nexus.xposed.runtime.util.call
import com.niki914.nexus.xposed.api.util.xTry

object BreenoFeedbackAssembler {
    fun attachIfNeeded(bean: Any) {
        val feedbackClassName = BreenoConfigProvider.RenderCard.feedbackInfoClass
        val footerClassName = BreenoConfigProvider.RenderCard.footerInfoClass
        val beanSetFeedbackInfoMethod = BreenoConfigProvider.RenderCard.beanSetFeedbackInfoMethod
        val feedbackSetFooterInfoMethod =
            BreenoConfigProvider.RenderCard.feedbackSetFooterInfoMethod
        val footerSetCopyFlagMethod = BreenoConfigProvider.RenderCard.footerInfoSetCopyFlagMethod
        val footerSetUpvoteFlagMethod =
            BreenoConfigProvider.RenderCard.footerInfoSetUpvoteFlagMethod

        val classLoader = bean.javaClass.classLoader
        val feedback = instantiate(feedbackClassName, classLoader) ?: return
        val footer = instantiate(footerClassName, classLoader) ?: return

        footer.call<Unit>(
            footerSetCopyFlagMethod,
            BreenoConfigProvider.RenderCard.feedbackCopyFlagEnabled
        )
        footer.call<Unit>(
            footerSetUpvoteFlagMethod,
            BreenoConfigProvider.RenderCard.feedbackUpvoteFlagEnabled
        )
        feedback.call<Unit>(feedbackSetFooterInfoMethod, footer)
        bean.call<Unit>(beanSetFeedbackInfoMethod, feedback)
    }

    private fun instantiate(className: String, classLoader: ClassLoader?): Any? = xTry {
        val clazz = Class.forName(className, false, classLoader)
        clazz.getDeclaredConstructor().newInstance()
    }
}
