package com.ireddragonicy.gamespace.profile

/** Common interface implemented by thermal and charging profile entities. */
interface NamedProfile {
    val id: String
    val name: String
    val isBuiltIn: Boolean
}

/** Trait implemented by profile repositories. */
interface ProfileRepository<T : NamedProfile> {
    fun all(): List<T>
    fun get(id: String): T?
    fun save(profile: T)
    fun delete(id: String): Boolean
}
