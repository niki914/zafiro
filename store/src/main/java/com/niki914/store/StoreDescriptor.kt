package com.niki914.store

data class StoreDescriptor(
    val id: String,
    val relativePath: String,
    val defaultJson: String = "{}"
)
