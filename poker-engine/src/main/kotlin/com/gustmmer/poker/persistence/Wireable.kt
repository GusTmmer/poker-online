package com.gustmmer.poker.persistence

interface Wireable<T> {
    fun toWire(): T
}
