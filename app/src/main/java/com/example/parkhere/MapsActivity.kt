package com.example.parkhere

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.roundToInt

/**
 * Shows an OpenStreetMap map around the user's current location and pins the
 * spot where they parked. The parked spot survives app restarts via
 * SharedPreferences.
 */
class MapsActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var parkButton: MaterialButton
    private lateinit var locationOverlay: MyLocationNewOverlay

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val preferences by lazy { getPreferences(Context.MODE_PRIVATE) }

    private var carMarker: Marker? = null
    private var accuracyCircle: Polygon? = null

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

        // Required by the tile provider's usage policy before the MapView loads.
        Configuration.getInstance().userAgentValue = "Parkhere/1.0 (Android student project)"

        setContentView(R.layout.activity_maps)

        mapView = findViewById(R.id.map)
        mapView.setTileSource(ESRI_WORLD_STREET)
        mapView.setMultiTouchControls(true)
        mapView.setTilesScaledToDpi(true)

        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        mapView.overlays.add(locationOverlay)

        parkButton = findViewById(R.id.park_button)
        parkButton.setOnClickListener { parkHere() }

        // Bring back the car icon from the last time the user parked.
        savedLocation()?.let {
            showCarMarker(it)
            val accuracy = savedAccuracy()
            if (accuracy > 0f) showAccuracyCircle(it, accuracy.toDouble())
            mapView.controller.setCenter(it)
            mapView.controller.setZoom(DEFAULT_ZOOM)
        }

        if (hasAnyLocationPermission()) {
            startLocationTracking()
        } else {
            ensureLocationPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        if (hasAnyLocationPermission()) {
            locationOverlay.enableMyLocation()
        }
    }

    override fun onPause() {
        locationOverlay.disableMyLocation()
        mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        mapView.onDetach()
        super.onDestroy()
    }

    private fun parkHere() {
        when {
            !hasAnyLocationPermission() -> ensureLocationPermission()
            !hasFineLocationPermission() -> showPreciseLocationDialog()
            else -> captureParkingSpot()
        }
    }

    /**
     * Asks for a high-accuracy fix. If the first fix is coarse, gives the GPS a
     * few seconds to improve before saving the better of the two.
     */
    @SuppressLint("MissingPermission")
    private fun captureParkingSpot() {
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { first ->
                val needsBetterFix = first != null &&
                    first.hasAccuracy() && first.accuracy > GOOD_ACCURACY_METERS
                if (!needsBetterFix) {
                    finishParking(first)
                    return@addOnSuccessListener
                }
                Toast.makeText(this, R.string.waiting_for_gps, Toast.LENGTH_SHORT).show()
                mainHandler.postDelayed({
                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener { second -> finishParking(betterOf(first!!, second)) }
                        .addOnFailureListener { finishParking(first) }
                }, ACCURACY_RETRY_DELAY_MS)
            }
            .addOnFailureListener { finishParking(null) }
    }

    private fun finishParking(location: Location?) {
        if (isFinishing || isDestroyed) return
        if (location == null) {
            Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val parkedAt = GeoPoint(location.latitude, location.longitude)
        val accuracy = if (location.hasAccuracy()) location.accuracy else 0f

        showCarMarker(parkedAt)
        if (accuracy > 0f) {
            showAccuracyCircle(parkedAt, accuracy.toDouble())
        } else {
            hideAccuracyCircle()
        }
        mapView.controller.animateTo(parkedAt)
        saveLocation(parkedAt, accuracy)

        val message = if (accuracy > 0f) {
            getString(R.string.parked_confirmation_accuracy, accuracy.roundToInt())
        } else {
            getString(R.string.parked_confirmation)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun betterOf(first: Location, second: Location?): Location {
        if (second == null) return first
        if (!first.hasAccuracy()) return second
        if (!second.hasAccuracy()) return first
        return if (second.accuracy < first.accuracy) second else first
    }

    // --- Location permission -------------------------------------------------

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasAnyLocationPermission(): Boolean =
        hasFineLocationPermission() ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    private fun ensureLocationPermission() {
        if (hasAnyLocationPermission()) {
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

    private fun showPreciseLocationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.precise_location_title)
            .setMessage(R.string.precise_location_message)
            .setPositiveButton(R.string.open_settings) { _, _ -> openAppSettings() }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    private fun showPermissionSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_permission_settings_title)
            .setMessage(R.string.location_permission_settings_message)
            .setPositiveButton(R.string.open_settings) { _, _ -> openAppSettings() }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }

    // --- Map and location ----------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        if (!hasAnyLocationPermission()) return
        locationOverlay.enableMyLocation()
        fetchCurrentLocation { location ->
            if (location == null) return@fetchCurrentLocation
            val current = GeoPoint(location.latitude, location.longitude)
            // Until the user parks, the pin sits on their current location.
            if (carMarker == null) showCarMarker(current)
            mapView.controller.setZoom(DEFAULT_ZOOM)
            mapView.controller.animateTo(current)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation(onResult: (Location?) -> Unit) {
        if (!hasAnyLocationPermission()) {
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

    private fun showCarMarker(point: GeoPoint) {
        val marker = carMarker
        if (marker == null) {
            val newMarker = Marker(mapView).apply {
                position = point
                title = getString(R.string.your_car)
                icon = carDrawable()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            carMarker = newMarker
            mapView.overlays.add(newMarker)
        } else {
            marker.position = point
        }
        mapView.invalidate()
    }

    private fun showAccuracyCircle(center: GeoPoint, radiusMeters: Double) {
        val points = Polygon.pointsAsCircle(center, radiusMeters)
        val circle = accuracyCircle
        if (circle == null) {
            val newCircle = Polygon(mapView).apply {
                setPoints(points)
                fillColor = 0x332196F3
                strokeColor = 0x882196F3.toInt()
                strokeWidth = 3f
            }
            accuracyCircle = newCircle
            // Draw underneath the location dot and the car pin.
            mapView.overlays.add(0, newCircle)
        } else {
            circle.setPoints(points)
        }
        mapView.invalidate()
    }

    private fun hideAccuracyCircle() {
        val circle = accuracyCircle ?: return
        mapView.overlays.remove(circle)
        accuracyCircle = null
        mapView.invalidate()
    }

    private fun carDrawable(): Drawable {
        val source = BitmapFactory.decodeResource(resources, R.drawable.car)
            ?: return ContextCompat.getDrawable(this, R.drawable.car)!!.apply {
                setBounds(0, 0, intrinsicWidth, intrinsicHeight)
            }
        val maxSize = (CAR_ICON_SIZE_DP * resources.displayMetrics.density).toInt()
        val largestSide = maxOf(source.width, source.height)
        val bitmap = if (largestSide > maxSize) {
            val ratio = maxSize.toFloat() / largestSide
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            source
        }
        return BitmapDrawable(resources, bitmap).apply {
            setBounds(0, 0, bitmap.width, bitmap.height)
        }
    }

    // --- Saved parking spot (bonus steps 9 and 10) ---------------------------

    private fun saveLocation(point: GeoPoint, accuracyMeters: Float) {
        preferences.edit().apply {
            putString(KEY_LATITUDE, point.latitude.toString())
            putString(KEY_LONGITUDE, point.longitude.toString())
            putFloat(KEY_ACCURACY, accuracyMeters)
            apply()
        }
    }

    private fun savedLocation(): GeoPoint? {
        val latitude = preferences.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = preferences.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        return GeoPoint(latitude, longitude)
    }

    private fun savedAccuracy(): Float = preferences.getFloat(KEY_ACCURACY, 0f)

    private companion object {
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_ACCURACY = "accuracy"
        const val KEY_PERMISSION_REQUESTED = "location_permission_requested"
        const val DEFAULT_ZOOM = 16.0
        const val CAR_ICON_SIZE_DP = 56
        const val GOOD_ACCURACY_METERS = 25f
        const val ACCURACY_RETRY_DELAY_MS = 3000L

        /**
         * Esri World Street Map raster tiles: no API key, attribution required.
         * Esri uses z/y/x tile order, hence the custom URL builder.
         */
        val ESRI_WORLD_STREET = object : OnlineTileSourceBase(
            "EsriWorldStreet",
            0,
            19,
            256,
            "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"),
            "Tiles © Esri — Esri, HERE, Garmin, USGS, © OpenStreetMap contributors"
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String =
                getBaseUrl() + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                    MapTileIndex.getY(pMapTileIndex) + "/" + MapTileIndex.getX(pMapTileIndex)
        }
    }
}
