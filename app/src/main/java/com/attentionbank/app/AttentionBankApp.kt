package com.attentionbank.app

import android.app.Application
import androidx.room.Room
import com.attentionbank.app.data.AttentionBankDatabase
import com.attentionbank.app.data.AttentionBankRepository

class AttentionBankApp : Application() {
    lateinit var repository: AttentionBankRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val database = Room.databaseBuilder(
            this,
            AttentionBankDatabase::class.java,
            "attention-bank.db",
        ).build()

        repository = AttentionBankRepository(
            context = this,
            database = database,
        )
    }
}
