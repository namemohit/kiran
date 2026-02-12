package com.example.cameraapp

import androidx.room.*

@Entity(tableName = "products")
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val price: Double,
    val imagePath: String? = null
)

@Entity(
    tableName = "product_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = Product::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("productId")]
)
data class ProductEmbedding(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val productId: Int,
    val vector: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ProductEmbedding
        if (id != other.id) return false
        if (productId != other.productId) return false
        if (!vector.contentEquals(other.vector)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + productId
        result = 31 * result + vector.contentHashCode()
        return result
    }
}

@Dao
interface ProductDao {
    @Insert
    suspend fun insertProduct(product: Product): Long

    @Insert
    suspend fun insertEmbedding(embedding: ProductEmbedding)

    @Query("SELECT * FROM products")
    suspend fun getAllProducts(): List<Product>

    @Transaction
    @Query("SELECT * FROM products WHERE id = :productId")
    suspend fun getProductWithEmbeddings(productId: Int): ProductWithEmbeddings

    @Transaction
    @Query("SELECT * FROM products")
    suspend fun getAllProductsWithEmbeddings(): List<ProductWithEmbeddings>

    @Query("SELECT * FROM product_embeddings")
    suspend fun getAllEmbeddings(): List<ProductEmbedding>

    @Query("DELETE FROM products WHERE id = :productId")
    suspend fun deleteProduct(productId: Int)
}

data class ProductWithEmbeddings(
    @Embedded val product: Product,
    @Relation(
        parentColumn = "id",
        entityColumn = "productId"
    )
    val embeddings: List<ProductEmbedding>
)

class Converters {
    @TypeConverter
    fun fromFloatArray(value: FloatArray): String {
        return value.joinToString(",")
    }

    @TypeConverter
    fun toFloatArray(value: String): FloatArray {
        if (value.isEmpty()) return floatArrayOf()
        return value.split(",").map { it.toFloat() }.toFloatArray()
    }
}

@Database(entities = [Product::class, ProductEmbedding::class], version = 1)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "camera_app_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
