package com.ugo.studio.plugins.flutter_p2p_connection

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.app.Application.ActivityLifecycleCallbacks
import android.content.BroadcastReceiver
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel.Result
import java.text.SimpleDateFormat
import java.util.HashMap
import java.util.*

/** FlutterP2pConnectionPlugin */
class FlutterP2pConnectionPlugin: FlutterPlugin, MethodCallHandler, ActivityAware {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private lateinit var channel : MethodChannel
  lateinit var context: Context
  lateinit var activity: Activity
  val intentFilter = IntentFilter()
  lateinit var wifimanager: WifiP2pManager
  lateinit var wifichannel: WifiP2pManager.Channel
  var receiver: BroadcastReceiver? = null
  var EfoundPeers: MutableList<String> = mutableListOf()
  private lateinit var CfoundPeers: EventChannel
  var EnetworkInfo: NetworkInfo? = null
  var EwifiP2pInfo: WifiP2pInfo? = null
  private lateinit var CConnectedPeers: EventChannel
  var groupClients: String = "[]"

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    context = flutterPluginBinding.applicationContext
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter_p2p_connection")
    channel.setMethodCallHandler(this)
    CfoundPeers = EventChannel(flutterPluginBinding.binaryMessenger, "flutter_p2p_connection_foundPeers")
    CfoundPeers.setStreamHandler(FoundPeersHandler)
    CConnectedPeers = EventChannel(flutterPluginBinding.binaryMessenger, "flutter_p2p_connection_connectedPeers")
    CConnectedPeers.setStreamHandler(ConnectedPeersHandler)
    // Set up the intent filter here (not in initialize()) so a resume() that
    // arrives before initialize() still registers a receiver that hears events,
    // and repeated initialize() calls don't accumulate duplicate actions.
    if (intentFilter.countActions() == 0) {
      intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
      intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
      intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
      intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }
  }

  private fun reasonString(reasonCode: Int): String {
    return when (reasonCode) {
      WifiP2pManager.ERROR -> "ERROR"
      WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
      WifiP2pManager.BUSY -> "BUSY"
      WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
      else -> "UNKNOWN($reasonCode)"
    }
  }

  private fun clearConnectionState() {
    EnetworkInfo = null
    EwifiP2pInfo = null
    groupClients = "[]"
  }

  private fun clientsJson(group: WifiP2pGroup?): String {
    if (group == null) return "[]"
    var clients: String = ""
    for (device: WifiP2pDevice in group.clientList) {
      val re = Regex("[^A-Za-z0-9 ']")
      val name = re.replace(device.deviceName, "")
      clients = clients + "{\"deviceName\": \"${name}\", \"deviceAddress\": \"${device.deviceAddress}\", \"isGroupOwner\": ${device.isGroupOwner}, \"isServiceDiscoveryCapable\": ${device.isServiceDiscoveryCapable}, \"primaryDeviceType\": \"${device.primaryDeviceType}\", \"secondaryDeviceType\": \"${device.secondaryDeviceType}\", \"status\": ${device.status}}, "
    }
    if (clients.length > 0) {
      clients = clients.subSequence(0, clients.length - 2).toString()
    }
    return "[${clients}]"
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    if (call.method == "getPlatformVersion") {
      result.success("Android: ${android.os.Build.VERSION.RELEASE}")
    } else if (call.method == "getPlatformModel") {
      result.success("model: ${android.os.Build.MODEL}")
    } else if (call.method == "initialize") {
      try {
        initializeWifiP2PConnections(result)
      } catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "discover") {
      try {
        discoverWifiPeers(result)
      } catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    }  else if (call.method == "stopDiscovery") {
      try {
        stopDiscoverWifiPeers(result)
      } catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "connect") {
      try {
        val address: String = call.argument("address") ?: ""
        connect(result, address)
      } catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "disconnect") {
      try {
        disconnect(result)
      } catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "disconnectFromAllPeers") {
      try {
        disconnectFromAllPeers(result)
      } catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "requestPeers") {
      try {
        peersListener()
      } catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "createGroup") {
      try {
        createGroup(result)
      }catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "removeGroup") {
      try {
        removeGroup(result)
      }catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "groupInfo") {
      try {
        requestGroupInfo(result)
      } catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "deviceInfo") {
      try {
        requestDeviceName(result)
      } catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "fetchPeers") {
      try {
        fetchPeers(result)
      }catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "resume") {
      try {
        resume(result)
      }catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "pause") {
      try {
        pause(result)
      }catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "checkLocationPermission") {
      try {
        checkLocationPermission(result)
      }catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "askLocationPermission") {
      try {
        askLocationPermission(result)
      }catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "checkLocationEnabled") {
      try {
        checkLocationEnabled(result)
      }catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "checkGpsEnabled") {
      try {
        checkGpsEnabled(result)
      }catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "enableLocationServices") {
      try {
        enableLocationServices(result)
      }catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "checkWifiEnabled") {
      try {
          checkWifiEnabled(result)
      }catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else if (call.method == "enableWifiServices") {
      try {
          enableWifiServices(result)
      }catch (e: Exception) {
        result.error("Err>>:", " ${e}", null)
      }
    } else {
      result.notImplemented()
    }
  }

  fun checkLocationPermission(result: Result) {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
      && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
      result.success(true);
    } else {
      result.success(false);
    }
  }

  fun askLocationPermission(result: Result) {
    val perms: Array<String> = arrayOf<String>(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
    activity.requestPermissions(perms, 2468)
    result.success(true)
  }

  fun checkLocationEnabled(result: Result) {
    var lm: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    result.success("${lm.isProviderEnabled(LocationManager.GPS_PROVIDER)}:${lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)}")
  }

  fun checkGpsEnabled(result: Result) {
    var lm: LocationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    result.success(lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
  }

  fun enableLocationServices(result: Result) {
    activity.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    result.success(true)
  }

  fun checkWifiEnabled(result: Result) {
    var wm: WifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    result.success(wm.isWifiEnabled)
  }

  fun enableWifiServices(result: Result) {
    activity.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
    result.success(true)
  }

  fun resume(result: Result) {
    // Re-registering without unregistering leaks the previous receiver and
    // delivers every broadcast N times.
    unregisterReceiverIfRegistered()
    receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        // Log.d(TAG, "FlutterP2pConnection: registered receiver")
        val action: String? = intent.action
        when (action) {
          WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
            // Check to see if Wi-Fi is enabled and notify appropriate activity
            val state: Int = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
            when (state) {
              WifiP2pManager.WIFI_P2P_STATE_ENABLED -> {
                // Wifi P2P is enabled
                Log.d(TAG, "FlutterP2pConnection: state enabled, Int=${state}")
              }
              else -> {
                // TODO: notify the user
                // Wi-Fi P2P is not enabled
                Log.d(TAG, "FlutterP2pConnection: state disabled, Int=${state}")
              }
            }
          }
          WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
            // Call WifiP2pManager.requestPeers() to get a list of current peers
            peersListener()
          }
          WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
            // Respond to new connection or disconnections
            wifimanager.requestGroupInfo(wifichannel, WifiP2pManager.GroupInfoListener { group: WifiP2pGroup? ->
              // A null group means it's gone; without the reset the stale
              // client list keeps the Dart side believing the connection is
              // still alive.
              groupClients = clientsJson(group)
            })
            val networkInfo: NetworkInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
            val wifiP2pInfo: WifiP2pInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
            if (networkInfo != null && wifiP2pInfo != null) {
              EnetworkInfo = networkInfo
              EwifiP2pInfo = wifiP2pInfo
              Log.d(TAG, "FlutterP2pConnection: connectionInfo={connected: ${networkInfo.isConnected}, isGroupOwner: ${wifiP2pInfo.isGroupOwner}, groupOwnerAddress: ${wifiP2pInfo.groupOwnerAddress}, groupFormed: ${wifiP2pInfo.groupFormed}, clients: ${groupClients}}")
              // Deliver the change to Dart now; the poll loop alone adds up
              // to a second of latency to every connect/disconnect.
              ConnectedPeersHandler.emitNow()
            }
          }
          WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
            // Respond to this device's wifi state changing
          }
        }
      }
    }
    context.registerReceiver(receiver, intentFilter)
    //Log.d(TAG, "FlutterP2pConnection: Initialized wifi p2p connection")
    result.success(true)
  }

  fun pause(result: Result) {
    unregisterReceiverIfRegistered()
    result.success(true)
  }

  private fun unregisterReceiverIfRegistered() {
    val currentReceiver = receiver ?: return
    receiver = null
    try {
      context.unregisterReceiver(currentReceiver)
    } catch (e: IllegalArgumentException) {
      Log.w(TAG, "FlutterP2pConnection: receiver was not registered: ${e}")
    }
  }

  fun initializeWifiP2PConnections(result: Result) {
    wifimanager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    wifichannel = wifimanager.initialize(context, Looper.getMainLooper(), null)
    result.success(true)
  }

  fun createGroup(result: Result) {
    wifimanager.createGroup(wifichannel, object : WifiP2pManager.ActionListener {
      override fun onSuccess() {
        Log.d(TAG, "FlutterP2pConnection: Created wifi p2p group")
        result.success(true)
      }

      override fun onFailure(reasonCode: Int) {
        Log.w(TAG, "FlutterP2pConnection: failed to create group, reason=${reasonString(reasonCode)}")
        result.success(false)
      }
    })
  }

  fun removeGroup(result: Result) {
    wifimanager.removeGroup(wifichannel, object : WifiP2pManager.ActionListener {
      override fun onSuccess() {
        Log.d(TAG, "FlutterP2pConnection: removed wifi p2p group")
        clearConnectionState()
        result.success(true)
      }

      override fun onFailure(reasonCode: Int) {
        Log.w(TAG, "FlutterP2pConnection: failed to remove group, reason=${reasonString(reasonCode)}")
        result.success(false)
      }
    })
  }

  fun requestGroupInfo(result: Result) {
    wifimanager.requestGroupInfo(wifichannel, WifiP2pManager.GroupInfoListener { group: WifiP2pGroup? ->
      if (group != null) {
        var clients: String = ""
        for (device: WifiP2pDevice in group.clientList) {
          val re = Regex("[^A-Za-z0-9 ']")
          val name = re.replace(device.deviceName, "") // 
          clients = clients + "{\"deviceName\": \"${name}\", \"deviceAddress\": \"${device.deviceAddress}\", \"isGroupOwner\": ${device.isGroupOwner}, \"isServiceDiscoveryCapable\": ${device.isServiceDiscoveryCapable}, \"primaryDeviceType\": \"${device.primaryDeviceType}\", \"secondaryDeviceType\": \"${device.secondaryDeviceType}\", \"status\": ${device.status}}, "
        }
        if (clients.length > 0) {
          clients = clients.subSequence(0, clients.length-2).toString()
        }
        Log.d(TAG, "FlutterP2pConnection: groupInfo={isGroupOwner: \"${group.isGroupOwner}\", passphrase: \"${group.passphrase}\", groupNetworkName: \"${group.networkName}\", \"clients\": \"${group.clientList.toString()}\"}")
        result.success("{\"isGroupOwner\": ${group.isGroupOwner}, \"passPhrase\": \"${group.passphrase}\", \"groupNetworkName\": \"${group.networkName}\", \"clients\": [${clients}]}")
      }
    })
  }

  fun discoverWifiPeers(result: Result) {
    wifimanager.discoverPeers(wifichannel, object : WifiP2pManager.ActionListener {
      override fun onSuccess() {
        Log.d(TAG, "FlutterP2pConnection: discovering wifi p2p devices")
        result.success(true);
      }
      override fun onFailure(reasonCode: Int) {
        Log.w(TAG, "FlutterP2pConnection: discovering wifi p2p devices failed, reason=${reasonString(reasonCode)}")
        result.success(false);
      }
    })
  }

  fun stopDiscoverWifiPeers(result: Result) {
    wifimanager.stopPeerDiscovery(wifichannel, object : WifiP2pManager.ActionListener {
      override fun onSuccess() {
        Log.d(TAG, "FlutterP2pConnection: stopped discovering wifi p2p devices")
        result.success(true);
      }
      override fun onFailure(reasonCode: Int) {
        Log.w(TAG, "FlutterP2pConnection: failed to stop discovering wifi p2p devices, reason=${reasonString(reasonCode)}")
        result.success(false);
      }
    })
  }

  fun requestDeviceName(result: Result) {

    if( android.os.Build.VERSION.SDK_INT <  android.os.Build.VERSION_CODES.Q){
        result.success("");
        return;
    }

    wifimanager.requestDeviceInfo(wifichannel, object : WifiP2pManager.DeviceInfoListener {
      override fun onDeviceInfoAvailable(device: WifiP2pDevice?) {
        if (device != null) {
          val re = Regex("[^A-Za-z0-9 ']")
          val name = re.replace(device.deviceName, "") // works
          Log.d(TAG, "FlutterP2pConnection: deviceInfo={deviceName: \"${device.deviceName}\", deviceAddress: \"${device.deviceAddress}\", isGroupOwner: ${device.isGroupOwner}, isServiceDiscoveryCapable: ${device.isServiceDiscoveryCapable}, primaryDeviceType: \"${device.primaryDeviceType}\", secondaryDeviceType: \"${device.secondaryDeviceType}\", status: ${device.status}}")
          result.success("{\"deviceName\": \"${name}\", \"deviceAddress\": \"${device.deviceAddress}\", \"isGroupOwner\": ${device.isGroupOwner}, \"isServiceDiscoveryCapable\": ${device.isServiceDiscoveryCapable}, \"primaryDeviceType\": \"${device.primaryDeviceType}\", \"secondaryDeviceType\": \"${device.secondaryDeviceType}\", \"status\": ${device.status}}")
        }
      }
    })
  }

  // Note: onSuccess here only means the framework ACCEPTED the connection
  // request — the actual connection result arrives asynchronously via
  // WIFI_P2P_CONNECTION_CHANGED_ACTION (surfaced through the connected-peers
  // event channel).
  fun connect(result: Result, address: String) {
    val config = WifiP2pConfig()
    config.deviceAddress = address
    config.wps.setup = WpsInfo.PBC
    wifichannel.also { wifichannel: WifiP2pManager.Channel ->
      wifimanager.connect(wifichannel, config, object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
          Log.d(TAG, "FlutterP2pConnection: connection request accepted, address=${address}")
          result.success(true);
        }
        override fun onFailure(reasonCode: Int) {
          Log.w(TAG, "FlutterP2pConnection: connection request to wifi p2p device failed, reason=${reasonString(reasonCode)}")
          result.success(false);
        }
      })
    }
  }

  fun disconnect(result: Result) {
    // cancelConnect only aborts an in-progress negotiation; an established
    // group must be removed explicitly or it lingers and blocks the next
    // connection attempt with BUSY.
    wifimanager.cancelConnect(wifichannel, object : WifiP2pManager.ActionListener {
      override fun onSuccess() {
        Log.d(TAG, "FlutterP2pConnection: cancelConnect succeeded")
      }
      override fun onFailure(reasonCode: Int) {
        Log.d(TAG, "FlutterP2pConnection: cancelConnect failed, reason=${reasonString(reasonCode)}")
      }
    })
    wifimanager.requestGroupInfo(wifichannel, WifiP2pManager.GroupInfoListener { group: WifiP2pGroup? ->
      if (group != null) {
        wifimanager.removeGroup(wifichannel, object : WifiP2pManager.ActionListener {
          override fun onSuccess() {
            Log.d(TAG, "FlutterP2pConnection: disconnect removed wifi p2p group")
            clearConnectionState()
            result.success(true)
          }
          override fun onFailure(reasonCode: Int) {
            Log.w(TAG, "FlutterP2pConnection: disconnect failed to remove group, reason=${reasonString(reasonCode)}")
            result.success(false)
          }
        })
      } else {
        result.success(true)
      }
    })
  }

  fun fetchPeers(result: Result) {
    result.success(EfoundPeers)
  }

  fun peersListener() {
    wifimanager.requestPeers(wifichannel, WifiP2pManager.PeerListListener { peers: WifiP2pDeviceList ->
      var list: MutableList<String> = mutableListOf()
      for (device: WifiP2pDevice in peers.deviceList) {
        val re = Regex("[^A-Za-z0-9 ']")
        val name = re.replace(device.deviceName, "") // works
        list.add("{\"deviceName\": \"${name}\", \"deviceAddress\": \"${device.deviceAddress}\", \"isGroupOwner\": ${device.isGroupOwner}, \"isServiceDiscoveryCapable\": ${device.isServiceDiscoveryCapable}, \"primaryDeviceType\": \"${device.primaryDeviceType}\", \"secondaryDeviceType\": \"${device.secondaryDeviceType}\", \"status\": ${device.status}}")
      }
      EfoundPeers = list
    })
  }

  fun disconnectFromAllPeers(result: Result) {
    wifimanager.requestGroupInfo(wifichannel, WifiP2pManager.GroupInfoListener { group: WifiP2pGroup? ->
      if (group != null) {
        wifimanager.removeGroup(wifichannel, object : WifiP2pManager.ActionListener {
          override fun onSuccess() {
            Log.d(TAG, "FlutterP2pConnection: disconnected from all peers")
            clearConnectionState()
            result.success(true);
          }
          override fun onFailure(reasonCode: Int) {
            Log.w(TAG, "FlutterP2pConnection: failed to disconnect from all peers, reason=${reasonString(reasonCode)}")
            result.success(false);
          }
        })
      } else {
        Log.d(TAG, "FlutterP2pConnection: failed to disconnect from all peers, group is null")
        result.success(true);
      }
    })
  }

  val FoundPeersHandler = object : EventChannel.StreamHandler {
    private var handler: Handler = Handler(Looper.getMainLooper())
    private var eventSink: EventChannel.EventSink? = null
    private var runnable: Runnable? = null

    override fun onListen(p0: Any?, sink: EventChannel.EventSink) {
      runnable?.let { handler.removeCallbacks(it) }
      eventSink = sink
      var peers: String = ""
      val r: Runnable = object : Runnable {
        override fun run() {
          handler.post {
            if (peers != EfoundPeers.toString()) {
              peers = EfoundPeers.toString()
              eventSink?.success(EfoundPeers)
            }
          }
          handler.postDelayed(this, 1000)
        }
      }
      runnable = r
      handler.postDelayed(r, 1000)
    }
    override fun onCancel(p0: Any?) {
      // Without removeCallbacks the old loop keeps running and double-emits
      // into whatever sink the next onListen installs.
      runnable?.let { handler.removeCallbacks(it) }
      runnable = null
      eventSink = null
    }
  }

  val ConnectedPeersHandler = ConnectedPeersStreamHandler()

  inner class ConnectedPeersStreamHandler : EventChannel.StreamHandler {
    private var handler: Handler = Handler(Looper.getMainLooper())
    private var eventSink: EventChannel.EventSink? = null
    private var runnable: Runnable? = null
    private var lastEmitted: String? = null

    // Push an event immediately instead of waiting for the next poll tick.
    // Called from the CONNECTION_CHANGED receiver: the poll added up to 1s
    // of latency on each phone before the Dart side learned the group
    // formed, and the connect flow pays that twice (once per side).
    fun emitNow() {
      handler.post { emitIfChanged() }
    }

    private fun currentPayload(): String {
      val ni: NetworkInfo? = EnetworkInfo
      val wi: WifiP2pInfo? = EwifiP2pInfo
      return if (ni != null && wi != null) {
        "{\"isConnected\": ${ni.isConnected}, \"isGroupOwner\": ${wi.isGroupOwner}, \"groupOwnerAddress\": \"${wi.groupOwnerAddress}\", \"groupFormed\": ${wi.groupFormed}, \"clients\": ${groupClients}}"
      } else {
        "null"
      }
    }

    // Main-looper only (reached via handler.post / postDelayed).
    private fun emitIfChanged() {
      val ni: NetworkInfo? = EnetworkInfo
      // While connected, keep the client list fresh: it is fetched
      // asynchronously and the join of a client does not always fire
      // another CONNECTION_CHANGED broadcast. The group owner's
      // Dart-side isConnected depends on this list being non-empty.
      if (ni != null && ni.isConnected && this@FlutterP2pConnectionPlugin::wifimanager.isInitialized) {
        wifimanager.requestGroupInfo(wifichannel, WifiP2pManager.GroupInfoListener { group: WifiP2pGroup? ->
          groupClients = clientsJson(group)
        })
      }
      // Compare the full payload rather than the info objects, so a
      // late-arriving client list still gets emitted. "null" (group
      // death) also falls out of this comparison, emitted exactly once.
      val payload: String = currentPayload()
      if (payload != lastEmitted) {
        lastEmitted = payload
        eventSink?.success(payload)
      }
    }

    override fun onListen(p0: Any?, sink: EventChannel.EventSink) {
      runnable?.let { handler.removeCallbacks(it) }
      eventSink = sink
      // Seed the dedupe with the CURRENT state instead of replaying it: a
      // fresh subscriber (every visit to the discovery screen) must not be
      // handed the stale connected-state of a session that just ended —
      // that ghost event kicked off a channel establishment against a dead
      // group. Subscribers only hear changes from this point on.
      lastEmitted = currentPayload()
      val r: Runnable = object : Runnable {
        override fun run() {
          emitIfChanged()
          handler.postDelayed(this, 1000)
        }
      }
      runnable = r
      handler.postDelayed(r, 1000)
    }
    override fun onCancel(p0: Any?) {
      runnable?.let { handler.removeCallbacks(it) }
      runnable = null
      eventSink = null
    }
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    CfoundPeers.setStreamHandler(null)
    CConnectedPeers.setStreamHandler(null)
  }

   override fun onDetachedFromActivity() {
//     TODO("Not yet implemented")
   }
   override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
     activity = binding.activity
   }
   override fun onAttachedToActivity(binding: ActivityPluginBinding) {
     activity = binding.activity
   }
   override fun onDetachedFromActivityForConfigChanges() {
//     TODO("Not yet implemented")
   }
}


