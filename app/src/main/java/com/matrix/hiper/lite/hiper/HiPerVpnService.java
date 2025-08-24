package com.matrix.hiper.lite.hiper;

import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Objects;

import mobile.CIDR;

// import com.matrix.hiper.lite.utils.LogUtils;

public class HiPerVpnService extends VpnService {

    private static String TAG = "VlanLite";

    private static boolean running = false;
    private static Sites.Site site = null;
    private static mobile.Bulk hiper = null;
    private static ParcelFileDescriptor vpnInterface = null;
    private NetworkCallback networkCallback = new NetworkCallback();
    private boolean didSleep = false;
    private NotificationManager notificationManager;
    private boolean isCallbackRegistered = false;

    private static HiPerCallback callback;

    public static boolean isRunning(String name) {
        return (site != null && running && Objects.equals(name, site.getName()));
    }

    public static Sites.Site getSite() {
        return site;
    }

    public static void setHiPerCallback(HiPerCallback callback) {
        HiPerVpnService.callback = callback;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent.getExtras().getBoolean("stop")) {
            stopVpn();
            return Service.START_NOT_STICKY;
        }

        if (running) {
            //TODO: can we signal failure?
            return super.onStartCommand(intent, flags, startId);
        }

        //TODO: if we fail to start, android will attempt a restart lacking all the intent data we need.
        // Link active site config in Main to avoid this
        site = Sites.Site.fromFile(getApplicationContext(), intent.getExtras().getString("name"));

        if (site.getCert() == null) {
            announceExit("Site is missing a certificate");
            //TODO: can we signal failure?
            return super.onStartCommand(intent, flags, startId);
        }

        startVpn();

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    private void startVpn() {
        CIDR ipNet;

        try {
            ipNet = mobile.Mobile.parseCIDR(site.getCert().getCert().getDetails().getIps().get(0));
        } catch (Exception e) {
            announceExit(e.toString());
            return;
        }

        Builder builder;
        builder = new Builder()
                .addAddress(ipNet.getIp(), (int) ipNet.getMaskSize())
                .addRoute(ipNet.getNetwork(), (int) ipNet.getMaskSize())
                .setMtu(site.getMtu())
                .setSession(TAG)
                .allowFamily(OsConstants.AF_INET)
                .allowFamily(OsConstants.AF_INET6);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false);
        }

        // Add our unsafe routes
        for (Sites.UnsafeRoute unsafeRoute : site.getUnsafeRoutes()) {
            try {
                CIDR cidr = mobile.Mobile.parseCIDR(unsafeRoute.getRoute());
                builder.addRoute(cidr.getNetwork(), (int) cidr.getMaskSize());
            } catch (Exception e) {
                announceExit(e.toString());
                e.printStackTrace();
            }
        }

        // Add our dns resolvers
        /*
        for (String dnsResolver : site.getDnsResolvers()) {
            builder.addDnsServer(dnsResolver);
        }

        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        for (Network network : connectivityManager.getAllNetworks()) {
            for (InetAddress addresses : connectivityManager.getLinkProperties(network).getDnsServers()) {
                builder.addDnsServer(addresses);
            }
        }

         */

        vpnInterface = builder.establish();
        System.out.println(site.getConfig());
        Handler handler = new Handler();
        new Thread(() -> {
            try {
                // LogUtils.d(site.getConfig());
                hiper = mobile.Mobile.newBulk(site.getConfig(), site.getLogFile(), vpnInterface.getFd());
                handler.post(() -> {
                    registerNetworkCallback();
                    //TODO: There is an open discussion around sleep killing tunnels or just changing mobile to tear down stale tunnels
                    //registerSleep()

                    hiper.start();
                    running = true;
                    sendSimple(1);
                });
            } catch (Exception e) {
                Log.e(TAG, "Got an error " + e);
                handler.post(() -> {
                    try {
                        vpnInterface.close();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    }
                    announceExit(e.toString());
                });
                stopSelf();
            }
        }).start();
    }

    private void stopVpn() {
        unregisterNetworkCallback();
        try {
            // 检查hiper对象是否已初始化
            if (hiper != null) {
                hiper.stop();
                hiper = null; // 清空引用
            }
            // 检查vpnInterface是否已初始化
            if (vpnInterface != null) {
                vpnInterface.close();
                vpnInterface = null; // 清空引用
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        running = false;
        site = null;
        announceExit(null);
    }

    // Used to detect network changes (wifi -> cell or vice versa) and rebinds the udp socket/updates LH
    private void registerNetworkCallback() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        isCallbackRegistered = true;
        connectivityManager.registerNetworkCallback(builder.build(), networkCallback);
    }

    private void unregisterNetworkCallback() {
        if (isCallbackRegistered) {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            connectivityManager.unregisterNetworkCallback(networkCallback);
            isCallbackRegistered = false;
        }
    }

    public class NetworkCallback extends ConnectivityManager.NetworkCallback {
        @Override
        public void onAvailable(@NonNull Network network) {
            super.onAvailable(network);
            if (hiper != null) {
                hiper.rebind("network change");
            }
        }

        @Override
        public void onLost(@NonNull Network network) {
            super.onLost(network);
            if (hiper != null) {
                hiper.rebind("network change");
            }
        }
    }

    private void registerSleep() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm.isDeviceIdleMode()) {
                    if (!didSleep) {
                        hiper.sleep();
                        //TODO: we may want to shut off our network change listener like we do with iOS, I haven't observed any issues with it yet though
                    }
                    didSleep = true;
                } else {
                    hiper.rebind("android wake");
                    didSleep = false;
                }
            }
        };

        registerReceiver(receiver, new IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED));
    }

    private void sendSimple(int code) {
        if (callback != null) {
            callback.run(code);
        }
    }

    private void announceExit(String err) {
        if (callback != null) {
            callback.onExit(err);
        }
    }

}

