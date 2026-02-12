package com.example.cameraapp

import android.content.Context
import android.util.Log
import kotlin.math.sqrt

/**
 * Repository to handle product data and embedding matching.
 */
class ProductRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val dao = db.productDao()

    suspend fun addProduct(name: String, price: Double, imagePath: String?, embeddings: List<FloatArray>) {
        val productId = dao.insertProduct(Product(name = name, price = price, imagePath = imagePath))
        embeddings.forEach {
            dao.insertEmbedding(ProductEmbedding(productId = productId.toInt(), vector = it))
        }
    }

    suspend fun addEmbedding(productId: Int, vector: FloatArray) {
        dao.insertEmbedding(ProductEmbedding(productId = productId, vector = vector))
    }

    suspend fun getOrCreateProductByName(name: String): Product {
        val trimmedName = name.trim()
        Log.d("ProductRepository", "getOrCreateProductByName: [$trimmedName]")
        
        val allProducts = dao.getAllProducts()
        val existing = allProducts.find { it.name.trim().equals(trimmedName, ignoreCase = true) }
        
        if (existing != null) {
            Log.d("ProductRepository", "Found existing product: ${existing.name} (ID: ${existing.id})")
            return existing
        }
        
        Log.i("ProductRepository", "Creating NEW product for: [$trimmedName]")
        val id = dao.insertProduct(Product(name = trimmedName, price = 0.0))
        val newProduct = Product(id = id.toInt(), name = trimmedName, price = 0.0)
        Log.i("ProductRepository", "New product created with ID: ${newProduct.id}")
        return newProduct
    }

    suspend fun findProductByName(name: String): Product? {
        val trimmedName = name.trim()
        return dao.getAllProducts().find { it.name.trim().equals(trimmedName, ignoreCase = true) }
    }

    suspend fun getAllProducts(): List<Product> = dao.getAllProducts()

    /**
     * Finds the product that best matches the target embedding.
     * Returns a Pair of Product and the confidence score (similarity).
     */
    suspend fun findBestMatch(targetEmbedding: FloatArray, threshold: Float = 0.7f): Pair<Product, Float>? {
        val allEmbeddings = dao.getAllEmbeddings()
        if (allEmbeddings.isEmpty()) return null

        var bestMatch: ProductEmbedding? = null
        var maxSimilarity = -1f

        for (embedding in allEmbeddings) {
            val similarity = calculateCosineSimilarity(targetEmbedding, embedding.vector)
            if (similarity > maxSimilarity) {
                maxSimilarity = similarity
                bestMatch = embedding
            }
        }

        return if (bestMatch != null && maxSimilarity >= threshold) {
            val productWithEmbeddings = dao.getProductWithEmbeddings(bestMatch.productId)
            productWithEmbeddings.product to maxSimilarity
        } else {
            null
        }
    }

    private fun calculateCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) return 0f
        
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }
        
        return if (normA > 0 && normB > 0) {
            (dotProduct / (sqrt(normA) * sqrt(normB))).toFloat()
        } else {
            0f
        }
    }
    
    suspend fun deleteProduct(productId: Int) {
        dao.deleteProduct(productId)
    }
}
