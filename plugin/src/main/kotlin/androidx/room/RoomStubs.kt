package androidx.room

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.RUNTIME)
annotation class Entity(val tableName: String = "", val indices: Array<Index> = [], val primaryKeys: Array<String> = [])

@Target(AnnotationTarget.FIELD) @Retention(AnnotationRetention.RUNTIME)
annotation class Index(val value: Array<String> = [], val unique: Boolean = false)

@Target(AnnotationTarget.FIELD) @Retention(AnnotationRetention.RUNTIME)
annotation class PrimaryKey(val autoGenerate: Boolean = false)

@Target(AnnotationTarget.FIELD) @Retention(AnnotationRetention.RUNTIME)
annotation class Ignore

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class TypeConverter

@Target(AnnotationTarget.FIELD) @Retention(AnnotationRetention.RUNTIME)
annotation class ColumnInfo(val name: String = "")

@Target(AnnotationTarget.FIELD) @Retention(AnnotationRetention.RUNTIME)
annotation class Embedded

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.RUNTIME)
annotation class Dao

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.RUNTIME)
annotation class Database(val entities: Array<KClass<*>> = [], val version: Int = 1)

@Target(AnnotationTarget.FIELD) @Retention(AnnotationRetention.RUNTIME)
annotation class Relation(val parentColumn: String = "", val entityColumn: String = "")

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class Transaction

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class Insert(val onConflict: Int = 0)

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class Update

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class Delete

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME)
annotation class Query(val value: String)

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.RUNTIME)
annotation class DatabaseView(val value: String = "", val viewName: String = "")

@Target(AnnotationTarget.ANNOTATION_CLASS) @Retention(AnnotationRetention.RUNTIME)
annotation class ForeignKey(
    val entity: KClass<*> = Any::class,
    val parentColumns: Array<String> = [],
    val childColumns: Array<String> = [],
    val onDelete: Int = 0,
    val onUpdate: Int = 0
)

object ForeignKeyConst {
    const val CASCADE = 0
    const val NO_ACTION = 1
    const val RESTRICT = 2
    const val SET_NULL = 3
    const val SET_DEFAULT = 4
}

@Retention(AnnotationRetention.SOURCE)
annotation class TypeConverters(vararg val value: KClass<*>)
