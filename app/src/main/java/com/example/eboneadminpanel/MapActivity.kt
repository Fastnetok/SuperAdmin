package com.example.superadmin

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.superadmin.core.CompanyDataHelper
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.firebase.database.*

class MapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private var mapReady = false

    private val employeeMarkers = mutableMapOf<String, Marker>()
    private var geofenceMarker: Marker? = null
    private var geofenceCircle: Circle? = null
    private var radiusCircle: Circle? = null
    private var selectedLocation: LatLng? = null

    private var radiusStepIndex = -1 // -1 = Off
    private val radiusSteps = listOf(
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        val radiusInput = findViewById<EditText>(R.id.radiusInput)
        val saveButton = findViewById<Button>(R.id.saveGeofenceButton)
        val radiusCycleButton = findViewById<Button>(R.id.radiusCycleButton)

        radiusCycleButton.setOnClickListener {
            toggleRadiusStep(radiusCycleButton)
        }

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mapReady = true
        mMap.mapType = GoogleMap.MAP_TYPE_SATELLITE

        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                mMap.isMyLocationEnabled = true
                mMap.uiSettings.isMyLocationButtonEnabled = true
            } catch (_: SecurityException) {}
        }

        // Tapping any employee / city marker -> smooth zoom in to that specific node area (17.5f)
        mMap.setOnMarkerClickListener { marker ->
            val title = marker.title ?: "Location"
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, 17.5f))
            marker.showInfoWindow()
            Toast.makeText(this, "Focused on: $title", Toast.LENGTH_SHORT).show()
            true
        }

        mMap.setOnMapLongClickListener { latLng ->
            selectedLocation = latLng
            geofenceMarker?.remove()
            geofenceCircle?.remove()

            geofenceMarker = mMap.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title("Geofence Center")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )

            geofenceCircle = mMap.addCircle(
                CircleOptions()
                    .center(latLng)
                    .radius(200.0)
                    .strokeWidth(4f)
                    .strokeColor(Color.RED)
                    .fillColor(0x30FF0000)
            )
            Toast.makeText(this, "Geofence Center Set", Toast.LENGTH_SHORT).show()
        }

        loadEmployeesAndCitiesOnMap()
    }

    private fun loadEmployeesAndCitiesOnMap() {
        val specificEmployeeId = intent.getStringExtra("employeeId")
        val db = FirebaseDatabase.getInstance()

        db.getReference("employees").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(empSnapshot: DataSnapshot) {
                db.getReference("employeePins").addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(pinsSnapshot: DataSnapshot) {
                        db.getReference("companies").addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(companiesSnapshot: DataSnapshot) {
                                if (!mapReady) return

                                val boundsBuilder = LatLngBounds.Builder()
                                var hasPoints = false

                                employeeMarkers.values.forEach { it.remove() }
                                employeeMarkers.clear()

                                val allChildren = mutableListOf<Pair<DataSnapshot, String?>>()
                                for (child in empSnapshot.children) allChildren.add(Pair(child, null))
                                for (child in pinsSnapshot.children) allChildren.add(Pair(child, null))

                                for (cSnap in companiesSnapshot.children) {
                                    val compKey = cSnap.key ?: continue
                                    for (child in cSnap.child("employees").children) allChildren.add(Pair(child, compKey))
                                    for (child in cSnap.child("employeePins").children) allChildren.add(Pair(child, compKey))
                                }

                                for (pair in allChildren) {
                                    val child = pair.first
                                    val parentCompKey = pair.second
                                    val empId = child.key ?: continue
                                    if (specificEmployeeId != null && empId != specificEmployeeId) continue

                                    // CRITICAL: Respect current SuperAdmin company/city scoping (Okara, Depalpur, etc.)
                                    if (!CompanyDataHelper.belongsToCurrentCompany(this@MapActivity, child, parentCompKey)) continue

                                    // Robust coordinate extraction matching MainActivity & AllEmployeesActivity
                                    val lat = child.child("latitude").value?.toString()?.toDoubleOrNull()
                                        ?: child.child("lat").value?.toString()?.toDoubleOrNull()
                                        ?: child.child("location").child("latitude").value?.toString()?.toDoubleOrNull()
                                        ?: child.child("location").child("lat").value?.toString()?.toDoubleOrNull()
                                        ?: child.child("lastLocation").child("latitude").value?.toString()?.toDoubleOrNull()
                                        ?: child.child("lastLocation").child("lat").value?.toString()?.toDoubleOrNull()
                                        ?: continue

                                    val lng = child.child("longitude").value?.toString()?.toDoubleOrNull()
                                        ?: child.child("lng").value?.toString()?.toDoubleOrNull()
                                        ?: child.child("location").child("longitude").value?.toString()?.toDoubleOrNull()
                                        ?: child.child("location").child("lng").value?.toString()?.toDoubleOrNull()
                                        ?: child.child("lastLocation").child("longitude").value?.toString()?.toDoubleOrNull()
                                        ?: child.child("lastLocation").child("lng").value?.toString()?.toDoubleOrNull()
                                        ?: continue

                                    val name = child.child("employeeName").value?.toString()
                                        ?: child.child("name").value?.toString()
                                        ?: "Employee"
                                    val city = child.child("city").value?.toString()
                                        ?: child.child("zone").value?.toString()
                                        ?: ""

                                    val latLng = LatLng(lat, lng)
                                    boundsBuilder.include(latLng)
                                    hasPoints = true

                                    val markerTitle = if (city.isNotBlank()) "$name ($city)" else name

                                    val marker = mMap.addMarker(
                                        MarkerOptions()
                                            .position(latLng)
                                            .title(markerTitle)
                                    )
                                    if (marker != null) {
                                        employeeMarkers[empId] = marker
                                    }
                                }

                                // Auto-zoom to fit all cities/employees on dashboard map for active company/city
                                if (hasPoints && specificEmployeeId == null) {
                                    try {
                                        val bounds = boundsBuilder.build()
                                        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                                    } catch (_: Exception) {}
                                } else if (hasPoints && specificEmployeeId != null) {
                                    val marker = employeeMarkers[specificEmployeeId]
                                    if (marker != null) {
                                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, 17f))
                                    }
                                }
                            }

                            override fun onCancelled(error: DatabaseError) {}
                        })
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@MapActivity, "Failed to load map data: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun toggleRadiusStep(button: Button) {
        if (!mapReady) return
        radiusStepIndex++
        if (radiusStepIndex >= radiusSteps.size) {
            radiusStepIndex = -1 // Off
            radiusCircle?.remove()
            radiusCircle = null
            button.text = "Off"
            Toast.makeText(this, "Radius: Off", Toast.LENGTH_SHORT).show()
        } else {
            val (meters, label) = radiusSteps[radiusStepIndex]
            button.text = label

            // Center on camera target or first marker
            val center = if (employeeMarkers.isNotEmpty()) {
                employeeMarkers.values.first().position
            } else {
                mMap.cameraPosition.target
            }

            radiusCircle?.remove()
            radiusCircle = mMap.addCircle(
                CircleOptions()
                    .center(center)
                    .radius(meters)
                    .strokeWidth(3f)
                    .strokeColor(Color.parseColor("#3F51B5"))
                    .fillColor(Color.parseColor("#223F51B5"))
            )

            val zoom = getZoomLevelForRadius(meters)
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(center, zoom))
            Toast.makeText(this, "Radius: $label", Toast.LENGTH_SHORT).show()
        }
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
