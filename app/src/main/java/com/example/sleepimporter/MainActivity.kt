package com.example.sleepimporter

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var healthConnectClient: HealthConnectClient
    private lateinit var statusText: TextView
    private lateinit var selectButton: Button
    private lateinit var importButton: Button
    private var selectedFileUri: Uri? = null

    private val permissions = setOf(
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedFileUri = it
            statusText.text = "File selezionato"
            importButton.isEnabled = true
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { 
        checkPermissionsAndEnableImport()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        selectButton = findViewById(R.id.selectButton)
        importButton = findViewById(R.id.importButton)

        try {
            healthConnectClient = HealthConnectClient.getOrCreate(this)
        } catch (e: Exception) {
            statusText.text = "Health Connect non disponibile"
            selectButton.isEnabled = false
            return
        }

        selectButton.setOnClickListener {
            filePickerLauncher.launch("application/json")
        }

        importButton.setOnClickListener {
            requestHealthPermissions()
        }

        checkPermissionsAndEnableImport()
    }

    private fun checkPermissionsAndEnableImport() {
        lifecycleScope.launch {
            try {
                val granted = healthConnectClient.permissionController.getGrantedPermissions()
                val hasAllPermissions = granted.containsAll(permissions)
                
                if (hasAllPermissions && selectedFileUri != null) {
                    importButton.isEnabled = true
                    statusText.text = "Pronto per importazione"
                }
            } catch (e: Exception) {
                statusText.text = "Errore permessi"
            }
        }
    }

    private fun requestHealthPermissions() {
        lifecycleScope.launch {
            try {
                val granted = healthConnectClient.permissionController.getGrantedPermissions()
                if (!granted.containsAll(permissions)) {
                    val intent = PermissionController.createRequestPermissionResultContract()
                        .createIntent(this@MainActivity, permissions)
                    requestPermissionLauncher.launch(intent)
                } else {
                    importSleepData()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importSleepData() {
        selectedFileUri?.let { uri ->
            lifecycleScope.launch {
                try {
                    statusText.text = "Importazione..."
                    importButton.isEnabled = false
                    
                    val importer = SleepImporter(healthConnectClient, this@MainActivity)
                    val result = importer.importFromJsonUri(uri)
                    
                    statusText.text = "Importati: ${result.successCount}\nSaltati: ${result.skippedCount}"
                    Toast.makeText(this@MainActivity, "Completato!", Toast.LENGTH_LONG).show()
                    
                    importButton.isEnabled = true
                } catch (e: Exception) {
                    statusText.text = "Errore: ${e.message}"
                    Toast.makeText(this@MainActivity, "Errore: ${e.message}", Toast.LENGTH_LONG).show()
                    importButton.isEnabled = true
                }
            }
        }
    }
}
