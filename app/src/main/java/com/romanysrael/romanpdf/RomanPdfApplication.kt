package com.romanysrael.romanpdf

import android.app.Application
import com.romanysrael.romanpdf.data.AppDatabase
import com.romanysrael.romanpdf.data.DocumentRepository

class RomanPdfApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val repository: DocumentRepository by lazy { DocumentRepository(this, database) }
}
