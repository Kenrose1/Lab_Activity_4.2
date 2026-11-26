package com.example.parkhere

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shows a map around the user's current location and pins the spot where they
 * parked. The parked spot survives app restarts via SharedPreferences.
 */
class MapsActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var map: GoogleMap
    private lateinit var parkButton: MaterialButton

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val preferences by lazy { getPreferences(Context.MODE_PRIVATE) }

    private var carMarker: Marker? = null

    private var permissionRequested: Boolean
        get() = preferences.getBoolean(KEY_PERMISSION_REQUESTED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_PERMISSION_REQUESTED, value).apply()
        }

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            when {
                granted -> startLocationTracking()
                !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ->
                    showPermissionSettingsDialog()
                else -> Toast.makeText(
                    this,
                    R.string.location_permission_denied,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)

        parkButton = findViewById(R.id.park_button)
        parkButton.setOnClickListener { parkHere() }

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        map.uiSettings.isZoomControlsEnabled = true

        // Bring back the car icon from the last time the user parked.
        savedLocation()?.let { showCarMarker(it) }

        if (hasLocationPermission()) {
            startLocationTracking()
        } else {
            ensureLocationPermission()
        }
    }

    private fun parkHere() {
        if (!hasLocationPermission()) {
            ensureLocationPermission()
            return
        }
        fetchCurrentLocation { location ->
            if (location == null) {
                Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_LONG).show()
                return@fetchCurrentLocation
            }
            val parkedAt = LatLng(location.latitude, location.longitude)
            showCarMarker(parkedAt)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(parkedAt, DEFAULT_ZOOM))
            saveLocation(parkedAt)
            Toast.makeText(this, R.string.parked_confirmation, Toast.LENGTH_SHORT).show()
        }
    }

    // --- Location permission -------------------------------------------------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) {
            startLocationTracking()
            return
        }
        when {
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.location_rationale_title)
                    .setMessage(R.string.location_rationale_message)
                    .setPositiveButton(R.string.ok) { _, _ -> launchPermissionRequest() }
                    .setNegativeButton(R.string.not_now, null)
                    .show()

            permissionRequested -> showPermissionSettingsDialog()

            else -> launchPermissionRequest()
        }
    }

    private fun launchPermissionRequest() {
        permissionRequested = true
        requestLocationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun showPermissionSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_permission_settings_title)
            .setMessage(R.string.location_permission_settings_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    // --- Map and location ----------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if (!::map.isInitialized || !hasLocationPermission()) return
        map.isMyLocationEnabled = true
        fetchCurrentLocation { location ->
            if (location == null) {
                savedLocation()?.let {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(it, DEFAULT_ZOOM))
                }
                return@fetchCurrentLocation
            }
            val current = LatLng(location.latitude, location.longitude)
            // Until the user parks, the pin sits on their current location.
            if (carMarker == null) showCarMarker(current)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(current, DEFAULT_ZOOM))
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation(onResult: (Location?) -> Unit) {
        if (!hasLocationPermission()) {
            onResult(null)
            return
        }
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    onResult(location)
                } else {
                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { lastLocation -> onResult(lastLocation) }
                        .addOnFailureListener { onResult(null) }
                }
            }
            .addOnFailureListener {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { lastLocation -> onResult(lastLocation) }
                    .addOnFailureListener { onResult(null) }
            }
    }

    private fun showCarMarker(latLng: LatLng) {
        if (!::map.isInitialized) return
        val icon = carIcon()
        val marker = carMarker
        if (marker == null) {
            carMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(getString(R.string.your_car))
                    .icon(icon)
                    .anchor(0.5f, 0.5f)
            )
        } else {
            marker.position = latLng
            marker.setIcon(icon)
        }
    }

    private fun carIcon(): BitmapDescriptor {
        val source = BitmapFactory.decodeResource(resources, R.drawable.car)
            ?: return BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
        val maxSize = (CAR_ICON_SIZE_DP * resources.displayMetrics.density).toInt()
        val largestSide = maxOf(source.width, source.height)
        if (largestSide <= maxSize) {
            return BitmapDescriptorFactory.fromBitmap(source)
        }
        val ratio = maxSize.toFloat() / largestSide
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1),
            true
        )
        return BitmapDescriptorFactory.fromBitmap(scaled)
    }

    // --- Saved parking spot (bonus steps 9 and 10) ---------------------------

    private fun saveLocation(latLng: LatLng) {
        preferences.edit().apply {
            putString(KEY_LATITUDE, latLng.latitude.toString())
            putString(KEY_LONGITUDE, latLng.longitude.toString())
            apply()
        }
    }

    private fun savedLocation(): LatLng? {
        val latitude = preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        return LatLng(latitude, longitude)
    }

    private companion object {
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_PERMISSION_REQUESTED = "location_permission_requested"
        const val DEFAULT_ZOOM = 16f
        const val CAR_ICON_SIZE_DP = 56
    }
}
