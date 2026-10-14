package com.example.superadmin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.InputType
import android.widget.EditText
import android.widget.TextView
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
        if (permissions.values.all { it }) {
            ContextCompat.startForegroundService(this, Intent(this, PaymentActivationService::class.java))
        } else {
            android.widget.Toast.makeText(this, "SMS aur Notification permission zaroori hai payment auto-activation ke liye", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                if (::mMap.isInitialized) {
                    try {
                        mMap.isMyLocationEnabled = true
                    } catch (_: SecurityException) {}
                }
            }
        }

    private lateinit var mMap: GoogleMap
    private var hasCenteredCameraOnce = false
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
        val neededPermissions = mutableListOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
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

    private var attendanceSnapshot: DataSnapshot? = null
    private var employeesSnapshot: DataSnapshot? = null
    private var attendanceListener: ValueEventListener? = null
    private var employeesListener: ValueEventListener? = null
    private var attendanceRef: com.google.firebase.database.DatabaseReference? = null
    private var employeesRef: com.google.firebase.database.DatabaseReference? = null

    private fun setupFirebaseListeners() {
        if (attendanceListener != null && attendanceRef != null) {
            attendanceRef?.removeEventListener(attendanceListener!!)
        }
        if (employeesListener != null && employeesRef != null) {
            employeesRef?.removeEventListener(employeesListener!!)
        }

        attendanceRef = CompanyDataHelper.getScopedDatabaseReference(this, "attendance")
        attendanceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                attendanceSnapshot = snapshot
                updateDashboardMap()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        attendanceRef?.addValueEventListener(attendanceListener!!)

        employeesRef = CompanyDataHelper.getScopedDatabaseReference(this, "employees")
        employeesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                employeesSnapshot = snapshot
                updateDashboardMap()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        employeesRef?.addValueEventListener(employeesListener!!)
    }

    private fun updateDashboardMap() {
        if (!::mMap.isInitialized) return
        val attSnapshot = attendanceSnapshot ?: return
        val empSnapshot = employeesSnapshot ?: return

        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val onlineList = mutableListOf<String>()
        val offlineList = mutableListOf<String>()
        var firstLocation: LatLng? = null

        val employeeDataMap = mutableMapOf<String, MutableList<DataSnapshot>>()

        for (employee in empSnapshot.children) {
            if (!CompanyDataHelper.belongsToCurrentCompany(this@MainActivity, employee)) continue

            val employeeName = employee.child("employeeName").value?.toString()?.takeIf { it.isNotBlank() }
                ?: employee.child("name").value?.toString()
                ?: "Employee"

            val normName = if (employeeName != "Employee") employeeName.trim().lowercase(Locale.getDefault()) else employee.key ?: "unknown"
            employeeDataMap.getOrPut(normName) { mutableListOf() }.add(employee)
        }

        val seenEmployeeNames = HashSet<String>()

        for ((normName, records) in employeeDataMap) {
            var hasAttendance = false
            var finalLat: Double? = null
            var finalLng: Double? = null
            var finalName = "Employee"

            for (record in records) {
                val deviceId = record.key ?: ""
                val androidId = record.child("androidId").value?.toString() ?: ""
                val uid = record.child("uid").value?.toString() ?: ""
                val phone = record.child("phoneNumber").value?.toString() ?: record.child("mobileNumber").value?.toString() ?: ""

                val name = record.child("employeeName").value?.toString()?.takeIf { it.isNotBlank() }
                    ?: record.child("name").value?.toString()
                    ?: "Employee"
                
                if (name != "Employee") {
                    finalName = name
                }

                if (attSnapshot.child(deviceId).hasChild(todayKey) ||
                    (androidId.isNotBlank() && attSnapshot.child(androidId).hasChild(todayKey)) ||
                    (uid.isNotBlank() && attSnapshot.child(uid).hasChild(todayKey)) ||
                    (phone.isNotBlank() && attSnapshot.child(phone).hasChild(todayKey)) ||
                    (name != "Employee" && attSnapshot.child(name).hasChild(todayKey))
                ) {
                    hasAttendance = true
                }

                val lat = record.child("latitude").value?.toString()?.toDoubleOrNull()
                val lng = record.child("longitude").value?.toString()?.toDoubleOrNull()
                if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                    finalLat = lat
                    finalLng = lng
                }
            }

            if (hasAttendance) {
                onlineList.add(finalName)
            } else {
                offlineList.add(finalName)
            }

            if (finalLat != null && finalLng != null) {
                val location = LatLng(finalLat, finalLng)
                seenEmployeeNames.add(finalName)

                val existingMarker = employeeMarkers[finalName]
                if (existingMarker != null) {
                    existingMarker.position = location
                    if (finalName == followedEmployeeName) {
                        existingMarker.showInfoWindow()
                    }
                } else {
                    val newMarker = mMap.addMarker(
                        MarkerOptions().position(location).title(finalName)
                    )
                    if (newMarker != null) {
                        employeeMarkers[finalName] = newMarker
                    }
                }

                if (finalName == followedEmployeeName) {
                    mMap.animateCamera(CameraUpdateFactory.newLatLng(location))
                }

                checkGeofenceTransitions(finalName, finalLat, finalLng)

                if (firstLocation == null) {
                    firstLocation = location
                }
            }
        }

        val markersToRemove = employeeMarkers.keys.filter { it !in seenEmployeeNames }
        for (name in markersToRemove) {
            employeeMarkers[name]?.remove()
            employeeMarkers.remove(name)
            if (name == followedEmployeeName) {
                followedEmployeeName = null
            }
        }

        onlineEmployeesText.text = "🟢 Online Employees (${onlineList.size})"
        onlineEmployeesNames.text = onlineList.joinToString(", ")

        offlineEmployeesText.text = "🔴 Offline Employees (${offlineList.size})"
        offlineEmployeesNames.text = offlineList.joinToString(", ")

        if (!hasCenteredCameraOnce) {
            firstLocation?.let {
                mMap.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        it,
                        15f
                    )
                )
                hasCenteredCameraOnce = true
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                mMap.isMyLocationEnabled = true
            } catch (_: SecurityException) {}

            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    val currentLatLng = LatLng(location.latitude, location.longitude)
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                }
            }
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        mMap.mapType = GoogleMap.MAP_TYPE_SATELLITE
        mMap.uiSettings.isMapToolbarEnabled = true

        mMap.setOnMarkerClickListener { marker ->
            followedEmployeeName = marker.title
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, 18f))
            marker.showInfoWindow()
            false
        }

        mMap.setOnMapLongClickListener { latLng ->
            showRadiusDialog(latLng)
        }

        loadDashboardGeofences()
        setupFirebaseListeners()
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
}