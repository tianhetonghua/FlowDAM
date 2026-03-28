package com.tower.dam.core

data class KernelIptablesState(
    val rulesActive: Boolean,
    val redirectUids: Set<Int>
)
