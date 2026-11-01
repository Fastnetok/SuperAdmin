package com.example.superadmin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import android.text.Html
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.location.Location
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.example.superadmin.core.CompanyDataHelper
import com.example.superadmin.core.FirestorePaths
import com.example.superadmin.core.SessionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(),
    OnMapReadyCallback {
    // Permission test: Small harmless change.

    // NEW: requests RECEIVE_SMS + READ_SMS + POST_NOTIFICATIONS at runtime
    // together — declaring them in the manifest alone is not enough.
    // This is what lets PaymentActivationService receive SMS AND show
    // its "Tap to Activate" notification with sound, without the user
    // ever needing to open Phone Settings manually.
    private val startupPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECEIVE_SMS] == true) {
            ContextCompat.startForegroundService(this, Intent(this, PaymentActivationService::class.java))
        }
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            enableMyLocationOnMap()
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                enableMyLocationOnMap()
            }
        }

    private lateinit var mMap: GoogleMap
    private val employeeMarkers = HashMap<String, Marker>()
    private var followedEmployeeName: String? = null

    // Geofence (Single Active Circle)
    private var currentGeofenceCircle: Circle? = null
    private var previewCircle: Circle? = null
    private data class DashboardGeofence(val lat: Double, val lng: Double, val radius: Double)
    private var currentGeofence: DashboardGeofence? = null
    private var lastUsedRadius: Double = 100.0
    private val employeeInsideGeofences = HashMap<String, MutableSet<String>>()

    private lateinit var onlineEmployeesText: TextView
    private lateinit var offlineEmployeesText: TextView

    private lateinit var onlineEmployeesNames: TextView
    private lateinit var offlineEmployeesNames: TextView

    private lateinit var totalComplaintText: TextView
    private lateinit var pendingComplaintText: TextView
    private lateinit var inProgressText: TextView
    private lateinit var resolvedText: TextView

    private var newConnectionBadge: android.view.View? = null
    private var centerBadgeCountText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_admin_dashboard)

        // NEW: check + request SMS AND Notification permissions together
        // before starting the payment listener service (was previously
        // requesting nothing for SMS, and requesting notification
        // separately/later in loadDashboardCounters()).
        val neededPermissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val allGranted = neededPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            ContextCompat.startForegroundService(this, Intent(this, PaymentActivationService::class.java))
        } else {
            startupPermissionLauncher.launch(neededPermissions.toTypedArray())
        }

        VersionChecker.checkForUpdate(this)

        findViewById<TextView>(R.id.dealerPanelButton).setOnClickListener {
            startActivity(Intent(this, DealerPanelActivity::class.java))
        }

        // NEW: "Active" quick link next to Dealer — opens Customer
        // Billing directly.
        findViewById<TextView>(R.id.activeCustomersButton).setOnClickListener {
            startActivity(Intent(this, CustomerBillingActivity::class.java))
        }

        // NEW: "Complaint" shortcut — opens AddComplaintActivity directly
        findViewById<TextView>(R.id.shortcutComplaintButton).setOnClickListener {
            startActivity(Intent(this, AddComplaintActivity::class.java))
        }

        // NEW: Permanent "NEW" shortcut button
        findViewById<TextView>(R.id.shortcutNewConnButton).setOnClickListener {
            startActivity(Intent(this, NewConnectionDashboardActivity::class.java))
        }

        findViewById<TextView>(R.id.menuButton)
            .setOnClickListener {

                startActivity(
                    Intent(
                        this,
                        MenuActivity::class.java
                    )
                )
            }

        onlineEmployeesText =
            findViewById(R.id.onlineEmployeesText)

        offlineEmployeesText =
            findViewById(R.id.offlineEmployeesText)

        onlineEmployeesNames =
            findViewById(R.id.onlineEmployeesNames)

        offlineEmployeesNames =
            findViewById(R.id.offlineEmployeesNames)

        totalComplaintText =
            findViewById(R.id.totalComplaintText)

        pendingComplaintText =
            findViewById(R.id.pendingComplaintText)

        pendingComplaintText
            .setOnClickListener {

                startActivity(

                    Intent(
                        this,
                        PendingSummaryActivity::class.java
                    )

                )

            }

        inProgressText =
            findViewById(R.id.inProgressText)

        inProgressText
            .setOnClickListener {

                startActivity(

                    Intent(
                        this,
                        ProgressActivity::class.java
                    )

                )

            }

        resolvedText =
            findViewById(R.id.resolvedText)

        resolvedText.setOnClickListener {

            startActivity(

                Intent(
                    this,
                    ResolvedSummaryActivity::class.java
                )

            )

        }
        totalComplaintText.setOnClickListener {

            startActivity(
                Intent(
                    this,
                    TotalComplaintsActivity::class.java
                )
            )

        }

        val mapFragment =
            supportFragmentManager
                .findFragmentById(
                    R.id.dashboardMap
                ) as SupportMapFragment

        mapFragment.getMapAsync(this)

        val dashboardRadiusCycleButton = findViewById<Button>(R.id.dashboardRadiusCycleButton)
        dashboardRadiusCycleButton.setOnClickListener {
            toggleDashboardRadiusStep(dashboardRadiusCycleButton)
        }
        dashboardRadiusCycleButton.setOnLongClickListener {
            dashboardRadiusStepIndex = dashboardRadiusSteps.indexOfFirst { it.first == 120000.0 }
            if (dashboardRadiusStepIndex == -1) dashboardRadiusStepIndex = dashboardRadiusSteps.size - 1
            val (meters, label) = dashboardRadiusSteps[dashboardRadiusStepIndex]
            dashboardRadiusCycleButton.text = label
            applyDashboardRadius(meters, label)
            true
        }

        loadDashboardCounters()
        setupNewConnectionBadge()
        setupCompanyFilter()

        // Clean up any duplicate employee records in Firebase
        FirebaseEmployeeDeduplicator.cleanDuplicateEmployees()

        // NEW: Startup session refresh for all 4 panels
        StartupSessionManager.startRefresh(this)
    }

    private fun setupNewConnectionBadge() {
        newConnectionBadge = findViewById(R.id.newConnectionBadge)
        centerBadgeCountText = findViewById(R.id.centerBadgeCountText)
        val shortcutNewBtn = findViewById<TextView>(R.id.shortcutNewConnButton)

        newConnectionBadge?.setOnClickListener {
            val intent = Intent(this, NewConnectionDashboardActivity::class.java)
            startActivity(intent)
        }

        FirebaseDatabase.getInstance().getReference("officeSettings/new_connections/pending")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val count = snapshot.childrenCount.toInt()
                    
                    if (count > 0) {
                        centerBadgeCountText?.text = count.toString()
                        newConnectionBadge?.visibility = android.view.View.VISIBLE
                    } else {
                        newConnectionBadge?.visibility = android.view.View.GONE
                    }

                    // Always update shortcut button text
                    shortcutNewBtn?.text = "NEW ($count)"
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun setupCompanyFilter() {
        val companyFilterBar = findViewById<View>(R.id.companyFilterBar) ?: return
        val companyFilterSpinner = findViewById<Spinner>(R.id.companyFilterSpinner) ?: return

        val sessionManager = SessionManager(this)
        val currentUserEmail = FirebaseAuth.getInstance().currentUser?.email?.trim()?.lowercase()
        val isSuperAdmin = sessionManager.isSuperAdmin() || currentUserEmail == "abbasok@gmail.com" || currentUserEmail?.contains("abbasok") == true

        if (!isSuperAdmin) {
            companyFilterBar.visibility = View.GONE
            return
        }

        companyFilterBar.visibility = View.VISIBLE

        val firestore = FirebaseFirestore.getInstance()
        firestore.collection(FirestorePaths.COMPANIES)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null) return@addSnapshotListener

                val displayList = mutableListOf<CharSequence>()
                val companyIdList = mutableListOf<String>()

                val allLabel: CharSequence = Html.fromHtml("<font color='#FFD700'>🌐</font> <font color='#FFFFFF'><b>ALL (All Companies)</b></font>", Html.FROM_HTML_MODE_LEGACY)
                displayList.add(allLabel)
                companyIdList.add("ALL")

                for (doc in snapshot.documents) {
                    val cId = doc.getString("companyId")?.trim() ?: doc.id.trim()
                    val cName = doc.getString("companyName")?.trim() ?: cId
                    val city = doc.getString("city")?.trim() ?: ""

                    val textVal = if (city.isNotBlank()) "$cName ($city - $cId)" else "$cName ($cId)"
                    val formatted: CharSequence = Html.fromHtml("<font color='#FFD700'>🏢</font> <font color='#FFFFFF'><b>$textVal</b></font>", Html.FROM_HTML_MODE_LEGACY)
                    displayList.add(formatted)
                    companyIdList.add(cId)
                }

                val adapter = ArrayAdapter(this, R.layout.layout_company_spinner_item, displayList)
                adapter.setDropDownViewResource(R.layout.layout_company_spinner_dropdown_item)
                companyFilterSpinner.adapter = adapter

                // Restore previous selection if exists
                val currentActiveCompany = sessionManager.getActiveCompanyId() ?: "ALL"
                val activeIndex = companyIdList.indexOfFirst { it.equals(currentActiveCompany, ignoreCase = true) }
                if (activeIndex >= 0) {
                    companyFilterSpinner.setSelection(activeIndex)
                }

                var isFirstTrigger = true
                companyFilterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val selectedId = companyIdList.getOrNull(position) ?: "ALL"
                        sessionManager.saveActiveCompany(selectedId)

                        if (!isFirstTrigger) {
                            setupFirebaseListeners()
                            loadDashboardCounters()
                        }
                        isFirstTrigger = false
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
    }

    private var rootEmployeesSnapshot: DataSnapshot? = null
    private var companiesSnapshotData: DataSnapshot? = null
    private var approvedDevicesSnapshot: DataSnapshot? = null
    private var attendanceSnapshot: DataSnapshot? = null
    private var trackingSnapshot: DataSnapshot? = null

    private val listeners = mutableListOf<ValueEventListener>()
    private val refs = mutableListOf<DatabaseReference>()

    private fun setupFirebaseListeners() {
        for (i in refs.indices) {
            refs.getOrNull(i)?.removeEventListener(listeners.getOrNull(i) ?: continue)
        }
        refs.clear()
        listeners.clear()

        val db = FirebaseDatabase.getInstance()

        val attRef = db.getReference("attendance")
        val attListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                attendanceSnapshot = snapshot
                runOnUiThread { updateDashboardMap() }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        attRef.addValueEventListener(attListener)
        refs.add(attRef); listeners.add(attListener)

        val empRef = db.getReference("employees")
        val empListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                rootEmployeesSnapshot = snapshot
                runOnUiThread { updateDashboardMap() }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        empRef.addValueEventListener(empListener)
        refs.add(empRef); listeners.add(empListener)

        val compRef = db.getReference("companies")
        val compListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                companiesSnapshotData = snapshot
                runOnUiThread { updateDashboardMap() }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        compRef.addValueEventListener(compListener)
        refs.add(compRef); listeners.add(compListener)

        val appRef = db.getReference("ApprovedDevices")
        val appListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                approvedDevicesSnapshot = snapshot
                runOnUiThread { updateDashboardMap() }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        appRef.addValueEventListener(appListener)
        refs.add(appRef); listeners.add(appListener)

        val trackRef = db.getReference("tracking")
        val trackListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                trackingSnapshot = snapshot
                runOnUiThread { updateDashboardMap() }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        trackRef.addValueEventListener(trackListener)
        refs.add(trackRef); listeners.add(trackListener)
    }

    private fun updateDashboardMap() {
        if (!::mMap.isInitialized) return
        val rootEmpSnap = rootEmployeesSnapshot
        val compSnap = companiesSnapshotData
        val appDevSnap = approvedDevicesSnapshot
        val attSnapshot = attendanceSnapshot
        val trackSnap = trackingSnapshot

        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val onlineSet = mutableSetOf<String>()
        val offlineSet = mutableSetOf<String>()

        val processedNames = HashSet<String>()
        val seenEmployeeNames = HashSet<String>()
        val allChildren = mutableListOf<Pair<DataSnapshot, String?>>()

        if (appDevSnap != null) {
            for (child in appDevSnap.children) allChildren.add(Pair(child, null))
        }
        if (rootEmpSnap != null) {
            for (child in rootEmpSnap.children) allChildren.add(Pair(child, null))
        }
        if (compSnap != null) {
            for (cSnap in compSnap.children) {
                val compKey = cSnap.key ?: ""
                for (child in cSnap.child("ApprovedDevices").children) allChildren.add(Pair(child, compKey))
                for (child in cSnap.child("employees").children) allChildren.add(Pair(child, compKey))
                for (child in cSnap.child("employeePins").children) allChildren.add(Pair(child, compKey))
            }
        }

        fun getLatestTrackingCoords(employeeKey: String, employeeName: String, phone: String, uid: String, androidId: String): Pair<Double?, Double?> {
            val trackSnap = trackingSnapshot ?: return Pair(null, null)

            val candidateTracks = mutableListOf<DataSnapshot>()
            val keysToCheck = listOfNotNull(
                employeeKey,
                employeeName,
                phone,
                uid,
                androidId
            ).filter { it.isNotBlank() }

            for (k in keysToCheck) {
                if (trackSnap.child(k).exists()) {
                    candidateTracks.add(trackSnap.child(k))
                }
            }

            for (childSnap in trackSnap.children) {
                val cKey = childSnap.key ?: continue
                if (keysToCheck.any { k -> cKey.equals(k, true) || cKey.contains(k, true) || k.contains(cKey, true) }) {
                    if (!candidateTracks.contains(childSnap)) candidateTracks.add(childSnap)
                }
            }

            for (empTrack in candidateTracks) {
                var latestLat: Double? = null
                var latestLng: Double? = null
                var maxTimestamp = -1L

                val directLat = empTrack.child("lat").getValue(Double::class.java) ?: empTrack.child("lat").value?.toString()?.toDoubleOrNull()
                    ?: empTrack.child("latitude").getValue(Double::class.java) ?: empTrack.child("latitude").value?.toString()?.toDoubleOrNull()
                val directLng = empTrack.child("lng").getValue(Double::class.java) ?: empTrack.child("lng").value?.toString()?.toDoubleOrNull()
                    ?: empTrack.child("longitude").getValue(Double::class.java) ?: empTrack.child("longitude").value?.toString()?.toDoubleOrNull()
                if (directLat != null && directLng != null) {
                    return Pair(directLat, directLng)
                }

                for (dateOrTs in empTrack.children) {
                    val lat = dateOrTs.child("lat").getValue(Double::class.java) ?: dateOrTs.child("lat").value?.toString()?.toDoubleOrNull()
                        ?: dateOrTs.child("latitude").getValue(Double::class.java) ?: dateOrTs.child("latitude").value?.toString()?.toDoubleOrNull()
                    val lng = dateOrTs.child("lng").getValue(Double::class.java) ?: dateOrTs.child("lng").value?.toString()?.toDoubleOrNull()
                        ?: dateOrTs.child("longitude").getValue(Double::class.java) ?: dateOrTs.child("longitude").value?.toString()?.toDoubleOrNull()
                    val ts = dateOrTs.child("timestamp").getValue(Long::class.java) ?: dateOrTs.key?.toLongOrNull() ?: 0L

                    if (lat != null && lng != null) {
                        if (ts >= maxTimestamp) {
                            maxTimestamp = ts
                            latestLat = lat
                            latestLng = lng
                        }
                    }

                    for (tsChild in dateOrTs.children) {
                        val subLat = tsChild.child("lat").getValue(Double::class.java) ?: tsChild.child("lat").value?.toString()?.toDoubleOrNull()
                            ?: tsChild.child("latitude").getValue(Double::class.java) ?: tsChild.child("latitude").value?.toString()?.toDoubleOrNull()
                        val subLng = tsChild.child("lng").getValue(Double::class.java) ?: tsChild.child("lng").value?.toString()?.toDoubleOrNull()
                            ?: tsChild.child("longitude").getValue(Double::class.java) ?: tsChild.child("longitude").value?.toString()?.toDoubleOrNull()
                        val subTs = tsChild.child("timestamp").getValue(Long::class.java) ?: tsChild.key?.toLongOrNull() ?: 0L

                        if (subLat != null && subLng != null) {
                            if (subTs >= maxTimestamp) {
                                maxTimestamp = subTs
                                latestLat = subLat
                                latestLng = subLng
                            }
                        }
                    }
                }

                if (latestLat != null && latestLng != null) {
                    return Pair(latestLat, latestLng)
                }
            }

            return Pair(null, null)
        }

        fun extractCoords(ds: DataSnapshot?): Pair<Double?, Double?> {
            if (ds == null || !ds.exists()) return Pair(null, null)
            val lat = ds.child("latitude").getValue(Double::class.java) ?: ds.child("latitude").value?.toString()?.toDoubleOrNull()
                ?: ds.child("lat").getValue(Double::class.java) ?: ds.child("lat").value?.toString()?.toDoubleOrNull()
                ?: ds.child("lastLatitude").value?.toString()?.toDoubleOrNull()
                ?: ds.child("checkInLatitude").value?.toString()?.toDoubleOrNull()
                ?: ds.child("location").child("latitude").value?.toString()?.toDoubleOrNull()
                ?: ds.child("location").child("lat").value?.toString()?.toDoubleOrNull()
                ?: ds.child("lastLocation").child("latitude").value?.toString()?.toDoubleOrNull()
                ?: ds.child("lastLocation").child("lat").value?.toString()?.toDoubleOrNull()
                ?: ds.child("tracking").child("latitude").value?.toString()?.toDoubleOrNull()

            val lng = ds.child("longitude").getValue(Double::class.java) ?: ds.child("longitude").value?.toString()?.toDoubleOrNull()
                ?: ds.child("lng").getValue(Double::class.java) ?: ds.child("lng").value?.toString()?.toDoubleOrNull()
                ?: ds.child("lastLongitude").value?.toString()?.toDoubleOrNull()
                ?: ds.child("checkInLongitude").value?.toString()?.toDoubleOrNull()
                ?: ds.child("location").child("longitude").value?.toString()?.toDoubleOrNull()
                ?: ds.child("location").child("lng").value?.toString()?.toDoubleOrNull()
                ?: ds.child("lastLocation").child("longitude").value?.toString()?.toDoubleOrNull()
                ?: ds.child("lastLocation").child("lng").value?.toString()?.toDoubleOrNull()
                ?: ds.child("tracking").child("longitude").value?.toString()?.toDoubleOrNull()

            return Pair(lat, lng)
        }

        android.util.Log.d("MainActivityMap", "updateDashboardMap: total children collected = ${allChildren.size}, targetComp=${CompanyDataHelper.getCurrentCompanyId(this)}")

        val validCompanyEmployeeNames = HashSet<String>()
        for (pair in allChildren) {
            val child = pair.first
            val parentCompKey = pair.second
            val employeeName = child.child("employeeName").value?.toString()?.takeIf { it.isNotBlank() }
                ?: child.child("name").value?.toString()
                ?: continue
            if (CompanyDataHelper.belongsToCurrentCompany(this@MainActivity, child, parentCompKey)) {
                validCompanyEmployeeNames.add(employeeName.trim().lowercase(Locale.getDefault()))
            }
        }

        val employeeRecordsByName = mutableMapOf<String, MutableList<Pair<DataSnapshot, String?>>>()

        for (pair in allChildren) {
            val child = pair.first
            val parentCompKey = pair.second
            val employeeName = child.child("employeeName").value?.toString()?.takeIf { it.isNotBlank() }
                ?: child.child("name").value?.toString()?.takeIf { it.isNotBlank() }
                ?: continue

            val normName = employeeName.trim().lowercase(Locale.getDefault())

            val belongs = CompanyDataHelper.belongsToCurrentCompany(this@MainActivity, child, parentCompKey)

            if (!belongs) continue

            employeeRecordsByName.getOrPut(normName) { mutableListOf() }.add(pair)
        }

        for ((normName, pairs) in employeeRecordsByName) {
            var employeeName = "Employee"
            var hasActiveApprovedDevice = false
            var isBlocked = false
            var isOnlineSignal = false
            var finalLat: Double? = null
            var finalLng: Double? = null

            for (pair in pairs) {
                val child = pair.first
                val key = child.key ?: continue
                val rawName = child.child("employeeName").value?.toString()?.takeIf { it.isNotBlank() }
                    ?: child.child("name").value?.toString()
                    ?: "Employee"
                if (rawName != "Employee") {
                    employeeName = rawName
                }

                val status = child.child("status").value?.toString()?.uppercase(Locale.getDefault()) ?: ""
                val deviceStatus = child.child("deviceStatus").value?.toString()?.uppercase(Locale.getDefault()) ?: ""
                val sessionStatus = child.child("sessionStatus").value?.toString()?.uppercase(Locale.getDefault()) ?: ""

                val isApprovedNode = (status == "APPROVED" || status == "ONLINE" || status == "CHECKED_IN" || status == "ACTIVE" || deviceStatus == "APPROVED")
                val isExplicitlyRevoked = (status == "DEREGISTERED" || status == "REVOKED" || deviceStatus == "REVOKED" || sessionStatus == "LOGOUT")

                if (isApprovedNode && !isExplicitlyRevoked) {
                    hasActiveApprovedDevice = true
                }

                if (status == "BLOCKED" || deviceStatus == "BLOCKED") {
                    isBlocked = true
                }

                val rootEmpRecord = rootEmpSnap?.child(key)
                val trackRecord = trackSnap?.child(key)

                val (cLat, cLng) = extractCoords(child)
                val (rLat, rLng) = extractCoords(rootEmpRecord)
                val (tLat, tLng) = extractCoords(trackRecord)
                val phone = child.child("phoneNumber").value?.toString() ?: child.child("mobileNumber").value?.toString() ?: ""
                val uid = child.child("uid").value?.toString() ?: ""
                val androidId = child.child("androidId").value?.toString() ?: ""
                val (trLat, trLng) = getLatestTrackingCoords(key, employeeName, phone, uid, androidId)

                val lat = cLat ?: rLat ?: tLat ?: trLat
                val lng = cLng ?: rLng ?: tLng ?: trLng

                if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                    finalLat = lat
                    finalLng = lng
                }

                val lastUpdate = child.child("lastUpdate").getValue(Long::class.java) 
                    ?: rootEmpRecord?.child("lastUpdate")?.getValue(Long::class.java) ?: 0L
                val isRecentUpdate = (System.currentTimeMillis() - lastUpdate) < 86400000L // within last 24h

                val nodeOnline = (status == "ONLINE" || status == "CHECKED_IN" || child.child("isOnline").value == true)
                if ((isApprovedNode || nodeOnline) && (nodeOnline || isRecentUpdate || (finalLat != null && finalLng != null))) {
                    isOnlineSignal = true
                }
            }

            // An employee is TRULY online if they have an active approved status, an online signal, and are not blocked
            val isTrulyOnline = hasActiveApprovedDevice && isOnlineSignal && !isBlocked

            if (isTrulyOnline) {
                onlineSet.add(employeeName)
            } else if (hasActiveApprovedDevice && !isBlocked) {
                offlineSet.add(employeeName)
            }

            if (isTrulyOnline && finalLat != null && finalLng != null && finalLat != 0.0 && finalLng != 0.0) {
                val location = LatLng(finalLat, finalLng)
                seenEmployeeNames.add(employeeName)
                val statusTitle = employeeName

                val existingMarker = employeeMarkers[employeeName]
                if (existingMarker != null) {
                    existingMarker.position = location
                    existingMarker.title = statusTitle
                    if (employeeName == followedEmployeeName) {
                        existingMarker.showInfoWindow()
                    }
                } else {
                    val newMarker = mMap.addMarker(
                        MarkerOptions()
                            .position(location)
                            .title(statusTitle)
                    )
                    if (newMarker != null) {
                        employeeMarkers[employeeName] = newMarker
                    }
                }

                if (employeeName == followedEmployeeName) {
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(location))
                }
            }
        }

        // Guarantee mutual exclusivity: offlineSet must not contain anyone present in onlineSet!
        offlineSet.removeAll(onlineSet)

        val markersToRemove = employeeMarkers.keys.filter { it !in seenEmployeeNames }
        for (name in markersToRemove) {
            employeeMarkers[name]?.remove()
            employeeMarkers.remove(name)
            if (name == followedEmployeeName) {
                followedEmployeeName = null
            }
        }

        val onlineList = onlineSet.toList()
        val offlineList = offlineSet.toList()

        onlineEmployeesText.text = "🟢 Online Employees (${onlineList.size})"
        onlineEmployeesNames.text = onlineList.joinToString(", ")

        offlineEmployeesText.text = "🔴 Offline Employees (${offlineList.size})"
        offlineEmployeesNames.text = offlineList.joinToString(", ")
    }

    override fun onResume() {
        super.onResume()
        enableMyLocationOnMap()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun enableMyLocationOnMap() {
        if (!::mMap.isInitialized) return

        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (hasFine || hasCoarse) {
            try {
                mMap.isMyLocationEnabled = true
                mMap.uiSettings.isMyLocationButtonEnabled = true

                val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

                fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        val userLatLng = LatLng(location.latitude, location.longitude)
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 16f))
                    }
                }

                try {
                    val locationRequest = com.google.android.gms.location.LocationRequest.Builder(
                        com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000
                    )
                        .setMaxUpdates(1)
                        .build()

                    fusedLocationClient.requestLocationUpdates(
                        locationRequest,
                        object : com.google.android.gms.location.LocationCallback() {
                            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                                val loc = result.lastLocation ?: return
                                val userLatLng = LatLng(loc.latitude, loc.longitude)
                                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 16f))
                            }
                        },
                        android.os.Looper.getMainLooper()
                    )
                } catch (_: Exception) {}

            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        
        // Default initial focus squarely on Pakistan (Okara / Punjab region)
        val pakistanCenter = LatLng(30.8138, 73.4532)
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(pakistanCenter, 6.5f))

        enableMyLocationOnMap()

        mMap.mapType = GoogleMap.MAP_TYPE_HYBRID
        mMap.uiSettings.isMapToolbarEnabled = true

        mMap.setOnMarkerClickListener { marker ->
            followedEmployeeName = marker.title
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, 17.5f))
            marker.showInfoWindow()
            true
        }

        mMap.setOnMapLongClickListener { latLng ->
            showRadiusDialog(latLng)
        }

        loadDashboardGeofences()
        setupFirebaseListeners()
        updateDashboardMap()
    }

    // ===================== GEOFENCE (SIMPLE CUSTOM INPUT + CONFIRM) =====================

    private fun showRadiusDialog(latLng: LatLng): Boolean {

        previewCircle?.remove()
        previewCircle = mMap.addCircle(
            CircleOptions()
                .center(latLng)
                .radius(lastUsedRadius)
                .strokeColor(0xFF2196F3.toInt())
                .strokeWidth(4f)
                .fillColor(0x332196F3)
        )

        val radiusInput = EditText(this)
        radiusInput.hint = "Radius in meters (e.g. 150)"
        radiusInput.inputType = InputType.TYPE_CLASS_NUMBER
        radiusInput.setText(lastUsedRadius.toInt().toString())

        AlertDialog.Builder(this)
            .setTitle("Set Geofence Radius")
            .setView(radiusInput)
            .setPositiveButton("OK") { _, _ ->
                val enteredRadius = radiusInput.text.toString().toDoubleOrNull()
                if (enteredRadius != null && enteredRadius > 0) {
                    previewCircle?.radius = enteredRadius

                    AlertDialog.Builder(this)
                        .setTitle("Confirm")
                        .setMessage("Replace the previous value with the new value (${enteredRadius.toInt()}m)?")
                        .setPositiveButton("Yes") { _, _ ->
                            lastUsedRadius = enteredRadius
                            createGeofence(latLng, enteredRadius)
                            previewCircle?.remove()
                            previewCircle = null
                        }
                        .setNegativeButton("No") { _, _ ->
                            previewCircle?.remove()
                            previewCircle = null
                        }
                        .setOnCancelListener {
                            previewCircle?.remove()
                            previewCircle = null
                        }
                        .show()
                } else {
                    previewCircle?.remove()
                    previewCircle = null
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                previewCircle?.remove()
                previewCircle = null
            }
            .setOnCancelListener {
                previewCircle?.remove()
                previewCircle = null
            }
            .show()

        return true
    }

    private fun createGeofence(latLng: LatLng, radiusMeters: Double) {
        val data = hashMapOf(
            "lat" to latLng.latitude,
            "lng" to latLng.longitude,
            "radius" to radiusMeters
        )
        FirebaseDatabase.getInstance()
            .getReference("dashboardGeofences")
            .child("active")
            .setValue(data)
    }

    private fun loadDashboardGeofences() {
        FirebaseDatabase.getInstance()
            .getReference("dashboardGeofences")
            .child("active")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {

                    currentGeofenceCircle?.remove()
                    currentGeofenceCircle = null
                    currentGeofence = null

                    val lat = snapshot.child("lat").getValue(Double::class.java)
                    val lng = snapshot.child("lng").getValue(Double::class.java)
                    val radius = snapshot.child("radius").getValue(Double::class.java)

                    if (lat != null && lng != null && radius != null) {
                        lastUsedRadius = radius
                        currentGeofence = DashboardGeofence(lat, lng, radius)

                        currentGeofenceCircle = mMap.addCircle(
                            CircleOptions()
                                .center(LatLng(lat, lng))
                                .radius(radius)
                                .strokeColor(0xFFFF0000.toInt())
                                .strokeWidth(4f)
                                .fillColor(0x22FF0000)
                        )
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun checkGeofenceTransitions(employeeName: String, lat: Double, lng: Double) {
        val geofence = currentGeofence ?: return
        val distanceResult = FloatArray(1)

        Location.distanceBetween(lat, lng, geofence.lat, geofence.lng, distanceResult)
        val isInsideNow = distanceResult[0] <= geofence.radius

        val wasInsideBefore = employeeInsideGeofences[employeeName]?.contains("active") == true

        if (isInsideNow != wasInsideBefore) {
            playGeofenceAlertSound()
        }

        if (isInsideNow) {
            employeeInsideGeofences[employeeName] = mutableSetOf("active")
        } else {
            employeeInsideGeofences[employeeName] = mutableSetOf()
        }
    }

    private fun playGeofenceAlertSound() {
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val mediaPlayer = MediaPlayer.create(this, notificationUri)
            mediaPlayer?.setOnCompletionListener { it.release() }
            mediaPlayer?.start()
        } catch (_: Exception) {
        }
    }

    // ===================== END GEOFENCE =====================

    private fun loadDashboardCounters() {
        AdminNotificationListener.startListening(this)
        FirebaseDatabase
            .getInstance()
            .getReference("complaints")
            .addValueEventListener(

                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        var compTotalAll = 0
                        var compResolvedAll = 0
                        var resolvedToday = 0
                        val progressEmployees = HashSet<String>()

                        val todayStart = java.util.Calendar.getInstance().apply {
                            set(java.util.Calendar.HOUR_OF_DAY, 0)
                            set(java.util.Calendar.MINUTE, 0)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                        }.timeInMillis

                        for (item in snapshot.children) {
                            if (!CompanyDataHelper.belongsToCurrentCompany(this@MainActivity, item)) continue

                            compTotalAll++

                            val status = item.child("status").getValue(String::class.java) ?: "Pending"
                            val assignedTo = item.child("assignedTo").getValue(String::class.java) ?: ""

                            if (status.equals("Resolved", true)) {
                                compResolvedAll++
                                val resolvedTime = item.child("resolvedTime").getValue(Long::class.java) ?: 0L
                                if (resolvedTime >= todayStart) {
                                    resolvedToday++
                                }
                            } else {
                                if (assignedTo.isNotEmpty()) {
                                    progressEmployees.add(assignedTo)
                                }
                            }
                        }

                        val progress = progressEmployees.size
                        var pending = compTotalAll - progress - compResolvedAll
                        if (pending < 0) pending = 0

                        val total = pending + progress

                        totalComplaintText.text = "$total\nTotal"
                        pendingComplaintText.text = "$pending\nPending"
                        inProgressText.text = "$progress\nProgress"
                        resolvedText.text = "$resolvedToday\nResolved"

                    }

                    override fun onCancelled(
                        error: DatabaseError
                    ) {
                    }

                }
            )

    }

    private var dashboardRadiusStepIndex = -1 // -1 = Off
    private val dashboardRadiusSteps = listOf(
        Pair(7000.0, "7 km"),
        Pair(14000.0, "14 km"),
        Pair(21000.0, "21 km"),
        Pair(28000.0, "28 km"),
        Pair(35000.0, "35 km"),
        Pair(40000.0, "40 km"), // Regional jump
        Pair(50000.0, "50 km"),
        Pair(65000.0, "65 km"),
        Pair(80000.0, "80 km"),
        Pair(100000.0, "100 km"),
        Pair(120000.0, "120 km") // Max Pakistan/regional coverage
    )

    private fun toggleDashboardRadiusStep(button: Button) {
        if (!::mMap.isInitialized) return
        dashboardRadiusStepIndex++
        if (dashboardRadiusStepIndex >= dashboardRadiusSteps.size) {
            dashboardRadiusStepIndex = -1 // Off
            button.text = "Off"
        } else {
            val (meters, label) = dashboardRadiusSteps[dashboardRadiusStepIndex]
            button.text = label
            applyDashboardRadius(meters, label)
        }
    }

    private fun applyDashboardRadius(meters: Double, label: String) {
        if (!::mMap.isInitialized) return

        var sumLat = 0.0
        var sumLng = 0.0
        val count = employeeMarkers.size

        val center: LatLng = if (count > 0) {
            for (marker in employeeMarkers.values) {
                sumLat += marker.position.latitude
                sumLng += marker.position.longitude
            }
            LatLng(sumLat / count, sumLng / count)
        } else {
            mMap.cameraPosition.target
        }

        val zoom = getZoomLevelForRadius(meters)
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(center, zoom))
    }

    private fun getZoomLevelForRadius(meters: Double): Float {
        return when {
            meters <= 7000 -> 12.5f
            meters <= 14000 -> 11.5f
            meters <= 21000 -> 11.0f
            meters <= 28000 -> 10.5f
            meters <= 35000 -> 10.2f
            meters <= 40000 -> 10.0f
            meters <= 50000 -> 9.5f
            meters <= 65000 -> 9.0f
            meters <= 80000 -> 8.5f
            meters <= 100000 -> 8.0f
            else -> 7.5f
        }
    }
}