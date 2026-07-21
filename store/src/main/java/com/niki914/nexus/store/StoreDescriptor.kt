package com.niki914.nexus.store

data class StoreDescriptor(
    val id: String,
    val relativePath: String,
    val defaultJson: String = "{}"
)
