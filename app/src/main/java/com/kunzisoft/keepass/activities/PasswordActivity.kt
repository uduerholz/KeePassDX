/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *     
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.activities

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.inputmethod.EditorInfo.IME_ACTION_DONE
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.commit
import com.google.android.material.snackbar.Snackbar
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.activities.dialogs.DuplicateUuidDialog
import com.kunzisoft.keepass.activities.helpers.*
import com.kunzisoft.keepass.activities.legacy.DatabaseLockActivity
import com.kunzisoft.keepass.activities.legacy.DatabaseModeActivity
import com.kunzisoft.keepass.app.database.CipherDatabaseEntity
import com.kunzisoft.keepass.autofill.AutofillComponent
import com.kunzisoft.keepass.autofill.AutofillHelper
import com.kunzisoft.keepass.biometric.AdvancedUnlockFragment
import com.kunzisoft.keepass.database.element.Database
import com.kunzisoft.keepass.database.element.database.DatabaseKDBX
import com.kunzisoft.keepass.database.exception.DuplicateUuidDatabaseException
import com.kunzisoft.keepass.database.exception.FileNotFoundDatabaseException
import com.kunzisoft.keepass.database.exception.LoadDatabaseException
import com.kunzisoft.keepass.database.file.DatabaseHeaderKDBX
import com.kunzisoft.keepass.education.PasswordActivityEducation
import com.kunzisoft.keepass.model.MainCredential
import com.kunzisoft.keepass.model.RegisterInfo
import com.kunzisoft.keepass.model.SearchInfo
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.ACTION_DATABASE_LOAD_TASK
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.CIPHER_ENTITY_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.DATABASE_URI_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.MAIN_CREDENTIAL_KEY
import com.kunzisoft.keepass.services.DatabaseTaskNotificationService.Companion.READ_ONLY_KEY
import com.kunzisoft.keepass.settings.PreferencesUtil
import com.kunzisoft.keepass.tasks.ActionRunnable
import com.kunzisoft.keepass.utils.BACK_PREVIOUS_KEYBOARD_ACTION
import com.kunzisoft.keepass.utils.MenuUtil
import com.kunzisoft.keepass.utils.UriUtil
import com.kunzisoft.keepass.view.KeyFileSelectionView
import com.kunzisoft.keepass.view.asError
import com.kunzisoft.keepass.viewmodels.AdvancedUnlockViewModel
import com.kunzisoft.keepass.viewmodels.DatabaseFileViewModel
import java.io.BufferedInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream


class PasswordActivity : DatabaseModeActivity(), AdvancedUnlockFragment.BuilderListener {

    // Views
    private var toolbar: Toolbar? = null
    private var filenameView: TextView? = null
    private var passwordView: EditText? = null
    private var keyFileSelectionView: KeyFileSelectionView? = null
    private var confirmButtonView: Button? = null
    private var yubikeyButtonView: Button? = null
    private var checkboxPasswordView: CompoundButton? = null
    private var checkboxKeyFileView: CompoundButton? = null
    private var infoContainerView: ViewGroup? = null
    private lateinit var coordinatorLayout: CoordinatorLayout
    private var advancedUnlockFragment: AdvancedUnlockFragment? = null

    private val mDatabaseFileViewModel: DatabaseFileViewModel by viewModels()
    private val mAdvancedUnlockViewModel: AdvancedUnlockViewModel by viewModels()

    private var mDefaultDatabase: Boolean = false
    private var mDatabaseFileUri: Uri? = null
    private var mDatabaseKeyFileUri: Uri? = null
    private var mChallenge: ByteArray? = null

    private var mRememberKeyFile: Boolean = false
    private var mExternalFileHelper: ExternalFileHelper? = null

    private var mReadOnly: Boolean = false
    private var mForceReadOnly: Boolean = false
        set(value) {
            infoContainerView?.visibility = if (value) {
                mReadOnly = true
                View.VISIBLE
            } else {
                View.GONE
            }
            field = value
        }

    private var mAutofillActivityResultLauncher: ActivityResultLauncher<Intent>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            AutofillHelper.buildActivityResultLauncher(this)
        else null

    private var mChallengeResponseActivityResultLauncher: ActivityResultLauncher<Intent>? = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
        Log.d(TAG, "resultCode from ykdroid: " + result.resultCode)
        if (result.resultCode == Activity.RESULT_OK) {
            val response: ByteArray? = result.data?.getByteArrayExtra("response")
            Log.d(TAG, "Response from yubikey: " + response.contentToString())
            response?.let {
                verifyCheckboxesAndLoadDatabase(response)
            }
        }
    }

    /*
     * seed: 32 byte transform seed, needs to be padded before sent to the yubikey
     */
    fun launchYubikeyActivity(seed: ByteArray) {
        val challenge = ByteArray(64)
        System.arraycopy(seed, 0, challenge, 0, 32)
        challenge.fill(32, 32, 64)
        val intent = Intent("net.pp3345.ykdroid.intent.action.CHALLENGE_RESPONSE");
        Log.d(TAG, "Challenge sent to yubikey: " + challenge.contentToString())
        intent.putExtra("challenge", challenge)
        mChallenge = challenge
        try {
            mChallengeResponseActivityResultLauncher?.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No activity to handle CHALLENGE_RESPONSE intent")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_password)

        toolbar = findViewById(R.id.toolbar)
        toolbar?.title = getString(R.string.app_name)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        confirmButtonView = findViewById(R.id.activity_password_open_button)
        filenameView = findViewById(R.id.filename)
        passwordView = findViewById(R.id.password)
        keyFileSelectionView = findViewById(R.id.keyfile_selection)
        checkboxPasswordView = findViewById(R.id.password_checkbox)
        checkboxKeyFileView = findViewById(R.id.keyfile_checkox)
        infoContainerView = findViewById(R.id.activity_password_info_container)
        coordinatorLayout = findViewById(R.id.activity_password_coordinator_layout)

        mReadOnly = if (savedInstanceState != null && savedInstanceState.containsKey(KEY_READ_ONLY)) {
            savedInstanceState.getBoolean(KEY_READ_ONLY)
        } else {
            PreferencesUtil.enableReadOnlyDatabase(this)
        }
        mRememberKeyFile = PreferencesUtil.rememberKeyFileLocations(this)

        mExternalFileHelper = ExternalFileHelper(this@PasswordActivity)
        mExternalFileHelper?.buildOpenDocument { uri ->
            if (uri != null) {
                mDatabaseKeyFileUri = uri
                populateKeyFileTextView(uri)
            }
        }
        keyFileSelectionView?.setOpenDocumentClickListener(mExternalFileHelper)

        passwordView?.setOnEditorActionListener(onEditorActionListener)
        passwordView?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}

            override fun onTextChanged(charSequence: CharSequence, i: Int, i1: Int, i2: Int) {}

            override fun afterTextChanged(editable: Editable) {
                if (editable.toString().isNotEmpty() && checkboxPasswordView?.isChecked != true)
                    checkboxPasswordView?.isChecked = true
            }
        })
        passwordView?.setOnKeyListener { _, _, keyEvent ->
            var handled = false
            if (keyEvent.action == KeyEvent.ACTION_DOWN
                && keyEvent?.keyCode == KEYCODE_ENTER) {
                verifyCheckboxesAndLoadDatabase()
                handled = true
            }
            handled
        }

        // If is a view intent
        getUriFromIntent(intent)
        if (savedInstanceState?.containsKey(KEY_KEYFILE) == true) {
            mDatabaseKeyFileUri = UriUtil.parse(savedInstanceState.getString(KEY_KEYFILE))
        }

        // Init Biometric elements
        advancedUnlockFragment = supportFragmentManager
                .findFragmentByTag(UNLOCK_FRAGMENT_TAG) as? AdvancedUnlockFragment?
        if (advancedUnlockFragment == null) {
            advancedUnlockFragment = AdvancedUnlockFragment()
            supportFragmentManager.commit {
                replace(R.id.fragment_advanced_unlock_container_view,
                        advancedUnlockFragment!!,
                        UNLOCK_FRAGMENT_TAG)
            }
        }

        // Listen password checkbox to init advanced unlock and confirmation button
        checkboxPasswordView?.setOnCheckedChangeListener { _, _ ->
            mAdvancedUnlockViewModel.checkUnlockAvailability()
            enableOrNotTheConfirmationButton()
        }

        // Observe if default database
        mDatabaseFileViewModel.isDefaultDatabase.observe(this) { isDefaultDatabase ->
            mDefaultDatabase = isDefaultDatabase
        }

        // Observe database file change
        mDatabaseFileViewModel.databaseFileLoaded.observe(this) { databaseFile ->
            // Force read only if the file does not exists
            mForceReadOnly = databaseFile?.let {
                !it.databaseFileExists
            } ?: true
            invalidateOptionsMenu()

            // Post init uri with KeyFile only if needed
            val keyFileUri =
                    if (mRememberKeyFile
                            && (mDatabaseKeyFileUri == null || mDatabaseKeyFileUri.toString().isEmpty())) {
                        databaseFile?.keyFileUri
                    } else {
                        mDatabaseKeyFileUri
                    }

            // Define title
            filenameView?.text = databaseFile?.databaseAlias ?: ""

            onDatabaseFileLoaded(databaseFile?.databaseUri, keyFileUri)
        }

        yubikeyButtonView = findViewById(R.id.activity_password_yubikey_button)
        val transformSeed = getTransformSeedFromHeader(mDatabaseFileUri!!, applicationContext.contentResolver)

        yubikeyButtonView?.setOnClickListener {
            transformSeed?.let {
                launchYubikeyActivity(transformSeed)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        mRememberKeyFile = PreferencesUtil.rememberKeyFileLocations(this@PasswordActivity)

        // Back to previous keyboard is setting activated
        if (PreferencesUtil.isKeyboardPreviousDatabaseCredentialsEnable(this@PasswordActivity)) {
            sendBroadcast(Intent(BACK_PREVIOUS_KEYBOARD_ACTION))
        }

        // Don't allow auto open prompt if lock become when UI visible
        if (DatabaseLockActivity.LOCKING_ACTIVITY_UI_VISIBLE_DURING_LOCK == true) {
            mAdvancedUnlockViewModel.allowAutoOpenBiometricPrompt = false
        }

        mDatabaseFileUri?.let { databaseFileUri ->
            mDatabaseFileViewModel.loadDatabaseFile(databaseFileUri)
        }

        mDatabase?.let { database ->
            launchGroupActivityIfLoaded(database)
        }

    }

    override fun onPause() {
        // Reinit locking activity UI variable
        DatabaseLockActivity.LOCKING_ACTIVITY_UI_VISIBLE_DURING_LOCK = null

        super.onPause()
    }

    override fun onDatabaseRetrieved(database: Database?) {
        super.onDatabaseRetrieved(database)
        if (database != null) {
            launchGroupActivityIfLoaded(database)
        }
    }

    override fun onDatabaseActionFinished(
        database: Database,
        actionTask: String,
        result: ActionRunnable.Result
    ) {
        super.onDatabaseActionFinished(database, actionTask, result)
        when (actionTask) {
            ACTION_DATABASE_LOAD_TASK -> {
                // Recheck advanced unlock if error
                mAdvancedUnlockViewModel.initAdvancedUnlockMode()

                if (result.isSuccess) {
                    launchGroupActivityIfLoaded(database)
                } else {
                    passwordView?.requestFocusFromTouch()

                    var resultError = ""
                    val resultException = result.exception
                    val resultMessage = result.message

                    if (resultException != null) {
                        resultError = resultException.getLocalizedMessage(resources)

                        when (resultException) {
                            is DuplicateUuidDatabaseException -> {
                                // Relaunch loading if we need to fix UUID
                                showLoadDatabaseDuplicateUuidMessage {

                                    var databaseUri: Uri? = null
                                    var mainCredential = MainCredential()
                                    var readOnly = true
                                    var cipherEntity: CipherDatabaseEntity? = null

                                    result.data?.let { resultData ->
                                        databaseUri = resultData.getParcelable(DATABASE_URI_KEY)
                                        mainCredential =
                                            resultData.getParcelable(MAIN_CREDENTIAL_KEY)
                                                ?: mainCredential
                                        readOnly = resultData.getBoolean(READ_ONLY_KEY)
                                        cipherEntity =
                                            resultData.getParcelable(CIPHER_ENTITY_KEY)
                                    }

                                    databaseUri?.let { databaseFileUri ->
                                        showProgressDialogAndLoadDatabase(
                                            databaseFileUri,
                                            mainCredential,
                                            readOnly,
                                            cipherEntity,
                                            true
                                        )
                                    }
                                }
                            }
                            is FileNotFoundDatabaseException -> {
                                // Remove this default database inaccessible
                                if (mDefaultDatabase) {
                                    mDatabaseFileViewModel.removeDefaultDatabase()
                                }
                            }
                        }
                    }

                    // Show error message
                    if (resultMessage != null && resultMessage.isNotEmpty()) {
                        resultError = "$resultError $resultMessage"
                    }
                    Log.e(TAG, resultError)
                    Snackbar.make(
                        coordinatorLayout,
                        resultError,
                        Snackbar.LENGTH_LONG
                    ).asError().show()
                }
            }
        }
    }

    private fun getUriFromIntent(intent: Intent?) {
        // If is a view intent
        val action = intent?.action
        if (action != null
                && action == VIEW_INTENT) {
            mDatabaseFileUri = intent.data
            mDatabaseKeyFileUri = UriUtil.getUriFromIntent(intent, KEY_KEYFILE)
        } else {
            mDatabaseFileUri = intent?.getParcelableExtra(KEY_FILENAME)
            mDatabaseKeyFileUri = intent?.getParcelableExtra(KEY_KEYFILE)
        }
        mDatabaseFileUri?.let {
            mDatabaseFileViewModel.checkIfIsDefaultDatabase(it)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        getUriFromIntent(intent)
    }

    private fun launchGroupActivityIfLoaded(database: Database) {
        // Check if database really loaded
        if (database.loaded) {
            clearCredentialsViews(true)
            GroupActivity.launch(this,
                database,
                { onValidateSpecialMode() },
                { onCancelSpecialMode() },
                { onLaunchActivitySpecialMode() },
                mAutofillActivityResultLauncher
            )
        }
    }

    override fun onValidateSpecialMode() {
        super.onValidateSpecialMode()
        finish()
    }

    override fun onCancelSpecialMode() {
        super.onCancelSpecialMode()
        finish()
    }

    override fun retrieveCredentialForEncryption(): String {
        return passwordView?.text?.toString() ?: ""
    }

    override fun conditionToStoreCredential(): Boolean {
        return checkboxPasswordView?.isChecked == true
    }

    override fun onCredentialEncrypted(databaseUri: Uri,
                                       encryptedCredential: String,
                                       ivSpec: String) {
        // Load the database if password is registered with biometric
        verifyCheckboxesAndLoadDatabase(
                CipherDatabaseEntity(
                        databaseUri.toString(),
                        encryptedCredential,
                        ivSpec)
        )
    }

    override fun onCredentialDecrypted(databaseUri: Uri,
                                       decryptedCredential: String) {
        // Load the database if password is retrieve from biometric
        // Retrieve from biometric
        verifyKeyFileCheckboxAndLoadDatabase(decryptedCredential)
    }

    private val onEditorActionListener = object : TextView.OnEditorActionListener {
        override fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?): Boolean {
            if (actionId == IME_ACTION_DONE) {
                verifyCheckboxesAndLoadDatabase()
                return true
            }
            return false
        }
    }

    private fun onDatabaseFileLoaded(databaseFileUri: Uri?, keyFileUri: Uri?) {
        // Define Key File text
        if (mRememberKeyFile) {
            populateKeyFileTextView(keyFileUri)
        }

        // Define listener for validate button
        confirmButtonView?.setOnClickListener { verifyCheckboxesAndLoadDatabase() }

        // If Activity is launch with a password and want to open directly
        val intent = intent
        val password = intent.getStringExtra(KEY_PASSWORD)
        // Consume the intent extra password
        intent.removeExtra(KEY_PASSWORD)
        val launchImmediately = intent.getBooleanExtra(KEY_LAUNCH_IMMEDIATELY, false)
        if (password != null) {
            populatePasswordTextView(password)
        }
        if (launchImmediately) {
            verifyCheckboxesAndLoadDatabase(password, keyFileUri)
        } else {
            // Init Biometric elements
            mAdvancedUnlockViewModel.databaseFileLoaded(databaseFileUri)
        }

        enableOrNotTheConfirmationButton()

        // Auto select the password field and open keyboard
        passwordView?.postDelayed({
            passwordView?.requestFocusFromTouch()
            val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager?
            inputMethodManager?.showSoftInput(passwordView, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    private fun enableOrNotTheConfirmationButton() {
        // Enable or not the open button if setting is checked
        if (!PreferencesUtil.emptyPasswordAllowed(this@PasswordActivity)) {
            checkboxPasswordView?.let {
                confirmButtonView?.isEnabled = (checkboxPasswordView?.isChecked == true
                        || checkboxKeyFileView?.isChecked == true)
            }
        } else {
            confirmButtonView?.isEnabled = true
        }
    }

    private fun clearCredentialsViews(clearKeyFile: Boolean = !mRememberKeyFile) {
        populatePasswordTextView(null)
        if (clearKeyFile) {
            mDatabaseKeyFileUri = null
            populateKeyFileTextView(null)
        }
    }

    private fun populatePasswordTextView(text: String?) {
        if (text == null || text.isEmpty()) {
            passwordView?.setText("")
            if (checkboxPasswordView?.isChecked == true)
                checkboxPasswordView?.isChecked = false
        } else {
            passwordView?.setText(text)
            if (checkboxPasswordView?.isChecked != true)
                checkboxPasswordView?.isChecked = true
        }
    }

    private fun populateKeyFileTextView(uri: Uri?) {
        if (uri == null || uri.toString().isEmpty()) {
            keyFileSelectionView?.uri = null
            if (checkboxKeyFileView?.isChecked == true)
                checkboxKeyFileView?.isChecked = false
        } else {
            keyFileSelectionView?.uri = uri
            if (checkboxKeyFileView?.isChecked != true)
                checkboxKeyFileView?.isChecked = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mDatabaseKeyFileUri?.let {
            outState.putString(KEY_KEYFILE, it.toString())
        }
        outState.putBoolean(KEY_READ_ONLY, mReadOnly)
        super.onSaveInstanceState(outState)
    }

    private fun verifyCheckboxesAndLoadDatabase(cipherDatabaseEntity: CipherDatabaseEntity? = null) {
        val password: String? = passwordView?.text?.toString()
        val keyFile: Uri? = keyFileSelectionView?.uri
        verifyCheckboxesAndLoadDatabase(password, keyFile, cipherDatabaseEntity)
    }

    private fun verifyCheckboxesAndLoadDatabase(yubikeyResponse: ByteArray, cipherDatabaseEntity: CipherDatabaseEntity? = null) {
        val keyPassword = if (checkboxPasswordView?.isChecked != true) null else passwordView?.text?.toString()
        val keyFile = if (checkboxKeyFileView?.isChecked != true) null else keyFileSelectionView?.uri
        mReadOnly = true
        loadDatabase(mDatabaseFileUri, keyPassword, keyFile, cipherDatabaseEntity, yubikeyResponse)
    }

    private fun getTransformSeedFromHeader(uri: Uri, contentResolver: ContentResolver): ByteArray? {
        var databaseInputStream: InputStream? = null
        var challenge: ByteArray? = null

        try {
            // Load Data, pass Uris as InputStreams
            val databaseStream = UriUtil.getUriInputStream(contentResolver, uri)
                    ?: throw IOException("Database input stream cannot be retrieve")

            databaseInputStream = BufferedInputStream(databaseStream)
            if (!databaseInputStream.markSupported()) {
                throw IOException("Input stream does not support mark.")
            }

            // We'll end up reading 8 bytes to identify the header. Might as well use two extra.
            databaseInputStream.mark(10)

            // Return to the start
            databaseInputStream.reset()

            val mDatabase = DatabaseKDBX()
            val header = DatabaseHeaderKDBX(mDatabase)

            header.loadFromFile(databaseInputStream)

            challenge = ByteArray(64)
            System.arraycopy(header.transformSeed, 0, challenge, 0, 32)
            challenge.fill(32, 32, 64)

        } catch (e: Exception) {
            Log.e(TAG, "Could not read transform seed from file")
            // throw LoadDatabaseException(e)
        } finally {
            databaseInputStream?.close()
        }

        return challenge
    }

    private fun verifyCheckboxesAndLoadDatabase(password: String?,
                                                keyFile: Uri?,
                                                cipherDatabaseEntity: CipherDatabaseEntity? = null) {
        val keyPassword = if (checkboxPasswordView?.isChecked != true) null else password
        verifyKeyFileCheckbox(keyFile)
        loadDatabase(mDatabaseFileUri, keyPassword, mDatabaseKeyFileUri, cipherDatabaseEntity)
    }

    private fun verifyKeyFileCheckboxAndLoadDatabase(password: String?) {
        val keyFile: Uri? = keyFileSelectionView?.uri
        verifyKeyFileCheckbox(keyFile)
        loadDatabase(mDatabaseFileUri, password, mDatabaseKeyFileUri)
    }

    private fun verifyKeyFileCheckbox(keyFile: Uri?) {
        mDatabaseKeyFileUri = if (checkboxKeyFileView?.isChecked != true) null else keyFile
    }

    private fun loadDatabase(databaseFileUri: Uri?,
                             password: String?,
                             keyFileUri: Uri?,
                             cipherDatabaseEntity: CipherDatabaseEntity? = null,
                             yubiResponse: ByteArray? = null) {

        if (PreferencesUtil.deletePasswordAfterConnexionAttempt(this)) {
            clearCredentialsViews()
        }

        if (mReadOnly && (
                mSpecialMode == SpecialMode.SAVE
                || mSpecialMode == SpecialMode.REGISTRATION)
        ) {
            Log.e(TAG, getString(R.string.autofill_read_only_save))
            Snackbar.make(coordinatorLayout,
                    R.string.autofill_read_only_save,
                    Snackbar.LENGTH_LONG).asError().show()
        } else {
            databaseFileUri?.let { databaseUri ->
                // Show the progress dialog and load the database
                showProgressDialogAndLoadDatabase(
                        databaseUri,
                        MainCredential(password, keyFileUri, yubiResponse),
                        mReadOnly,
                        cipherDatabaseEntity,
                        false)
            }
        }
    }

    private fun showProgressDialogAndLoadDatabase(databaseUri: Uri,
                                                  mainCredential: MainCredential,
                                                  readOnly: Boolean,
                                                  cipherDatabaseEntity: CipherDatabaseEntity?,
                                                  fixDuplicateUUID: Boolean) {
        loadDatabase(
                databaseUri,
                mainCredential,
                readOnly,
                cipherDatabaseEntity,
                fixDuplicateUUID
        )
    }

    private fun showLoadDatabaseDuplicateUuidMessage(loadDatabaseWithFix: (() -> Unit)? = null) {
        DuplicateUuidDialog().apply {
            positiveAction = loadDatabaseWithFix
        }.show(supportFragmentManager, "duplicateUUIDDialog")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        // Read menu
        inflater.inflate(R.menu.open_file, menu)
        if (mForceReadOnly) {
            menu.removeItem(R.id.menu_open_file_read_mode_key)
        } else {
            changeOpenFileReadIcon(menu.findItem(R.id.menu_open_file_read_mode_key))
        }

        if (mSpecialMode == SpecialMode.DEFAULT) {
            MenuUtil.defaultMenuInflater(inflater, menu)
        }

        super.onCreateOptionsMenu(menu)

        launchEducation(menu)

        return true
    }

    // To fix multiple view education
    private var performedEductionInProgress = false
    private fun launchEducation(menu: Menu) {
        if (!performedEductionInProgress) {
            performedEductionInProgress = true
            // Show education views
            Handler(Looper.getMainLooper()).post { performedNextEducation(PasswordActivityEducation(this), menu) }
        }
    }

    private fun performedNextEducation(passwordActivityEducation: PasswordActivityEducation,
                                       menu: Menu) {
        val educationToolbar = toolbar
        val unlockEducationPerformed = educationToolbar != null
                && passwordActivityEducation.checkAndPerformedUnlockEducation(
                educationToolbar,
                        {
                            performedNextEducation(passwordActivityEducation, menu)
                        },
                        {
                            performedNextEducation(passwordActivityEducation, menu)
                        })
        if (!unlockEducationPerformed) {
            val readOnlyEducationPerformed =
                    educationToolbar?.findViewById<View>(R.id.menu_open_file_read_mode_key) != null
                    && passwordActivityEducation.checkAndPerformedReadOnlyEducation(
                    educationToolbar.findViewById(R.id.menu_open_file_read_mode_key),
                    {
                        try {
                            menu.findItem(R.id.menu_open_file_read_mode_key)
                        } catch (e: Exception) {
                            Log.e(TAG, "Unable to find read mode menu")
                        }
                        performedNextEducation(passwordActivityEducation, menu)
                    },
                    {
                        performedNextEducation(passwordActivityEducation, menu)
                    })

            advancedUnlockFragment?.performEducation(passwordActivityEducation,
                    readOnlyEducationPerformed,
                    {
                        performedNextEducation(passwordActivityEducation, menu)
                    },
                    {
                        performedNextEducation(passwordActivityEducation, menu)
                    })
        }
    }

    private fun changeOpenFileReadIcon(togglePassword: MenuItem) {
        if (mReadOnly) {
            togglePassword.setTitle(R.string.menu_file_selection_read_only)
            togglePassword.setIcon(R.drawable.ic_read_only_white_24dp)
        } else {
            togglePassword.setTitle(R.string.menu_open_file_read_and_write)
            togglePassword.setIcon(R.drawable.ic_read_write_white_24dp)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {

        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.menu_open_file_read_mode_key -> {
                mReadOnly = !mReadOnly
                changeOpenFileReadIcon(item)
            }
            else -> MenuUtil.onDefaultMenuOptionsItemSelected(this, item)
        }

        return super.onOptionsItemSelected(item)
    }

    companion object {

        private val TAG = PasswordActivity::class.java.name

        private const val UNLOCK_FRAGMENT_TAG = "UNLOCK_FRAGMENT_TAG"

        private const val KEY_FILENAME = "fileName"
        private const val KEY_KEYFILE = "keyFile"
        private const val VIEW_INTENT = "android.intent.action.VIEW"

        private const val KEY_READ_ONLY = "KEY_READ_ONLY"
        private const val KEY_PASSWORD = "password"
        private const val KEY_LAUNCH_IMMEDIATELY = "launchImmediately"

        private fun buildAndLaunchIntent(activity: Activity, databaseFile: Uri, keyFile: Uri?,
                                         intentBuildLauncher: (Intent) -> Unit) {
            val intent = Intent(activity, PasswordActivity::class.java)
            intent.putExtra(KEY_FILENAME, databaseFile)
            if (keyFile != null)
                intent.putExtra(KEY_KEYFILE, keyFile)
            intentBuildLauncher.invoke(intent)
        }

        /*
         * -------------------------
         * 		Standard Launch
         * -------------------------
         */

        @Throws(FileNotFoundException::class)
        fun launch(activity: Activity,
                   databaseFile: Uri,
                   keyFile: Uri?) {
            buildAndLaunchIntent(activity, databaseFile, keyFile) { intent ->
                activity.startActivity(intent)
            }
        }

        /*
         * -------------------------
         * 		Share Launch
         * -------------------------
         */

        @Throws(FileNotFoundException::class)
        fun launchForSearchResult(activity: Activity,
                                  databaseFile: Uri,
                                  keyFile: Uri?,
                                  searchInfo: SearchInfo) {
            buildAndLaunchIntent(activity, databaseFile, keyFile) { intent ->
                EntrySelectionHelper.startActivityForSearchModeResult(
                        activity,
                        intent,
                        searchInfo)
            }
        }

        /*
         * -------------------------
         * 		Save Launch
         * -------------------------
         */

        @Throws(FileNotFoundException::class)
        fun launchForSaveResult(activity: Activity,
                                databaseFile: Uri,
                                keyFile: Uri?,
                                searchInfo: SearchInfo) {
            buildAndLaunchIntent(activity, databaseFile, keyFile) { intent ->
                EntrySelectionHelper.startActivityForSaveModeResult(
                        activity,
                        intent,
                        searchInfo)
            }
        }

        /*
         * -------------------------
         * 		Keyboard Launch
         * -------------------------
         */

        @Throws(FileNotFoundException::class)
        fun launchForKeyboardResult(activity: Activity,
                                    databaseFile: Uri,
                                    keyFile: Uri?,
                                    searchInfo: SearchInfo?) {
            buildAndLaunchIntent(activity, databaseFile, keyFile) { intent ->
                EntrySelectionHelper.startActivityForKeyboardSelectionModeResult(
                        activity,
                        intent,
                        searchInfo)
            }
        }

        /*
         * -------------------------
         * 		Autofill Launch
         * -------------------------
         */

        @RequiresApi(api = Build.VERSION_CODES.O)
        @Throws(FileNotFoundException::class)
        fun launchForAutofillResult(activity: AppCompatActivity,
                                    databaseFile: Uri,
                                    keyFile: Uri?,
                                    activityResultLauncher: ActivityResultLauncher<Intent>?,
                                    autofillComponent: AutofillComponent,
                                    searchInfo: SearchInfo?) {
            buildAndLaunchIntent(activity, databaseFile, keyFile) { intent ->
                AutofillHelper.startActivityForAutofillResult(
                        activity,
                        intent,
                        activityResultLauncher,
                        autofillComponent,
                        searchInfo)
            }
        }

        /*
         * -------------------------
         * 		Registration Launch
         * -------------------------
         */
        fun launchForRegistration(activity: Activity,
                                  databaseFile: Uri,
                                  keyFile: Uri?,
                                  registerInfo: RegisterInfo?) {
            buildAndLaunchIntent(activity, databaseFile, keyFile) { intent ->
                EntrySelectionHelper.startActivityForRegistrationModeResult(
                        activity,
                        intent,
                        registerInfo)
            }
        }

        /*
         * -------------------------
         * 		Global Launch
         * -------------------------
         */
        fun launch(activity: AppCompatActivity,
                   databaseUri: Uri,
                   keyFile: Uri?,
                   fileNoFoundAction: (exception: FileNotFoundException) -> Unit,
                   onCancelSpecialMode: () -> Unit,
                   onLaunchActivitySpecialMode: () -> Unit,
                   autofillActivityResultLauncher: ActivityResultLauncher<Intent>?) {

            try {
                EntrySelectionHelper.doSpecialAction(activity.intent,
                        {
                            PasswordActivity.launch(activity,
                                    databaseUri, keyFile)
                        },
                        { searchInfo -> // Search Action
                            PasswordActivity.launchForSearchResult(activity,
                                    databaseUri, keyFile,
                                    searchInfo)
                            onLaunchActivitySpecialMode()
                        },
                        { searchInfo -> // Save Action
                            PasswordActivity.launchForSaveResult(activity,
                                    databaseUri, keyFile,
                                    searchInfo)
                            onLaunchActivitySpecialMode()
                        },
                        { searchInfo -> // Keyboard Selection Action
                            PasswordActivity.launchForKeyboardResult(activity,
                                    databaseUri, keyFile,
                                    searchInfo)
                            onLaunchActivitySpecialMode()
                        },
                        { searchInfo, autofillComponent -> // Autofill Selection Action
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                PasswordActivity.launchForAutofillResult(activity,
                                        databaseUri, keyFile,
                                        autofillActivityResultLauncher,
                                        autofillComponent,
                                        searchInfo)
                                onLaunchActivitySpecialMode()
                            } else {
                                onCancelSpecialMode()
                            }
                        },
                        { registerInfo -> // Registration Action
                            PasswordActivity.launchForRegistration(activity,
                                    databaseUri, keyFile,
                                    registerInfo)
                            onLaunchActivitySpecialMode()
                        }
                )
            } catch (e: FileNotFoundException) {
                fileNoFoundAction(e)
            }
        }
    }
}
