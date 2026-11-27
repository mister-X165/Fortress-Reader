package com.fortress.reader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.fortress.reader.ui.FortressRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()
    private var pickerOpen = false
    private var authenticating = false

    private val anyFile = arrayOf("*/*")
    private val imageTypes = arrayOf("image/*")

    private val importDocPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        pickerOpen = false; it?.let { uri -> vm.prepareImport(uri, ImportTarget.VAULT_DOCUMENT) }
    }
    private val importPhotoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        pickerOpen = false; it?.let { uri -> vm.prepareImport(uri, ImportTarget.VAULT_PHOTO) }
    }
    private val tempPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        pickerOpen = false; it?.let { uri -> vm.prepareImport(uri, ImportTarget.TEMPORARY_VIEW) }
    }

    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        pickerOpen = false
        it?.let { uri -> vm.addLibraryFolder(uri) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) vm.refreshLibrary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        vm.markScreenProtectionActive()
        requestLibraryPermissions()
        setContent {
            FortressRoot(
                vm = vm,
                onUnlock = ::authenticate,
                onImportDoc = {
                    pickerOpen = true
                    importDocPicker.launch(anyFile)
                },
                onImportPhoto = {
                    pickerOpen = true
                    importPhotoPicker.launch(imageTypes)
                },
                onOpenTemporary = {
                    pickerOpen = true
                    tempPicker.launch(anyFile)
                },
                onAddFolder = {
                    pickerOpen = true
                    treePicker.launch(null)
                },
            )
        }
    }

    private fun requestLibraryPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) needed += Manifest.permission.READ_MEDIA_IMAGES
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
        else vm.refreshLibrary()
    }

    override fun onResume() {
        super.onResume()
        if (vm.locked.value && !authenticating) authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !pickerOpen) vm.lock()
    }

    private fun authenticate() {
        if (authenticating) return
        val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(
                this,
                "Set a screen lock (PIN, pattern or biometrics) to use Fortress Reader",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Fortress Reader")
            .setAllowedAuthenticators(authenticators)
            .build()
        authenticating = true
        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticating = false
                    vm.unlock()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticating = false
                    Toast.makeText(this@MainActivity, errString, Toast.LENGTH_SHORT).show()
                }
            },
        ).authenticate(info)
    }
}
