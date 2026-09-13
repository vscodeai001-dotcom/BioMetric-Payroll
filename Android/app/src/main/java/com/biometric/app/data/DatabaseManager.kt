package com.biometric.app.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

/**
 * DatabaseManager handles direct JDBC connection to the Neon PostgreSQL database.
 */
object DatabaseManager {
    private const val TAG = "DatabaseManager"
    
    private const val HOST = "ep-twilight-water-ayvo4m3p-pooler.c-5.us-east-2.aws.neon.tech"
    private const val DB_NAME = "neondb"
    private const val USER = "neondb_owner"
    private const val PASS = "npg_Sa3OuPEVeR8Y"
    
    // Direct JDBC URL. Using maxResultBuffer in URL to avoid JMX parsing logic in some driver versions.
    private const val JDBC_URL = "jdbc:postgresql://$HOST:5432/$DB_NAME?sslmode=require&maxResultBuffer=67108864"

    /**
     * Establishes a direct connection to the database.
     */
    suspend fun getConnection(): Connection? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Attempting direct JDBC connection to Neon...")
        
        try {
            // Step 0: Pre-verify DNS to give better error message
            if (!verifyDns()) {
                Log.e(TAG, "DNS resolution failed for $HOST")
                return@withContext null
            }

            // Step 1: Load driver
            Class.forName("org.postgresql.Driver")
            
            val props = Properties()
            props.setProperty("user", USER)
            props.setProperty("password", PASS)
            
            // Set timeout for login
            DriverManager.setLoginTimeout(20)
            
            // Step 2: Establish connection
            val conn = DriverManager.getConnection(JDBC_URL, props)
            Log.d(TAG, "JDBC connection successful")
            
            // Step 3: Health check
            if (verifyConnection(conn)) {
                conn
            } else {
                conn.close()
                null
            }
        } catch (e: SQLException) {
            Log.e(TAG, "SQL Exception: ${e.message} (State: ${e.sqlState})")
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal JDBC error (${t::class.java.simpleName}): ${t.message}")
            throw t
        }
    }

    private fun verifyDns(): Boolean {
        return try {
            val address = InetAddress.getByName(HOST)
            Log.d(TAG, "DNS OK: ${address.hostAddress}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "DNS Failed: ${e.message}")
            false
        }
    }

    private fun verifyConnection(conn: Connection): Boolean {
        return try {
            conn.prepareStatement("SELECT 1").use { stmt ->
                stmt.executeQuery().use { rs ->
                    val result = if (rs.next()) rs.getInt(1) else 0
                    Log.d(TAG, "SELECT 1 result: $result")
                    result == 1
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Verify query failed: ${e.message}")
            false
        }
    }
}
