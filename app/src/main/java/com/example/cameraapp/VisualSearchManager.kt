package com.example.cameraapp

import androidx.lifecycle.MutableLiveData

/**
 * Singleton to manage active visual search state and training mode.
 */
object VisualSearchManager {
    
    // Product being trained
    var trainingProductId: Int? = null
        private set
    
    // LiveData for UI updates
    val trainingProduct = MutableLiveData<Product?>()
    
    fun startTraining(product: Product) {
        trainingProductId = product.id
        trainingProduct.postValue(product)
    }
    
    fun stopTraining() {
        trainingProductId = null
        trainingProduct.postValue(null)
    }
}
