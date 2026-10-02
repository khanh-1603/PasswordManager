package com.example.passwordmanager.data.sync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

public class NetworkManager {
    private static final String TAG = "NetworkManager";

    private final ConnectivityManager connectivityManager;
    private final SyncManager syncManager;

    private boolean isNetworkAvailable = false;

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    super.onAvailable(network);

                    Log.d(TAG, "Mạng đã kết nối");

                    if (!isNetworkAvailable) {
                        isNetworkAvailable = true;

                        Log.d(TAG, "Mạng trở lại -> bắt đầu đồng bộ");

                        syncManager.sync();
                        syncManager.pullFromFirebase();
                    }
                }

                @Override
                public void onLost(Network network) {
                    super.onLost(network);

                    Log.d(TAG, "Mất mạng");

                    isNetworkAvailable = false;
                }
            };

    public static boolean isNetworkConnected(Context context) {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(
                        Context.CONNECTIVITY_SERVICE
                );

        if (connectivityManager == null) return false;

        Network activeNetwork = connectivityManager.getActiveNetwork();

        if (activeNetwork == null) return false;

        NetworkCapabilities capabilities
                = connectivityManager.getNetworkCapabilities(activeNetwork);

        return capabilities != null && (
                capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI
                )
                        || capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_CELLULAR
                )
        );
    }

    public NetworkManager(Context context, SyncManager syncManager) {
        connectivityManager =
                (ConnectivityManager) context.getSystemService(
                        Context.CONNECTIVITY_SERVICE
                );

        this.syncManager = syncManager;
    }

    public void startMonitoring() {
        if (connectivityManager == null) {
            Log.e(TAG, "ConnectivityManager là null, không thể theo dõi mạng");
            return;
        }

        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();

            connectivityManager.registerNetworkCallback(
                    request,
                    networkCallback
            );

            Log.d(TAG, "Bắt đầu theo dõi mạng");

        } catch (Exception e) {
            Log.e(TAG, "Không thể đăng ký NetworkCallback", e);
        }
    }

    public void stopMonitoring() {
        if (connectivityManager == null) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            Log.d(TAG, "Dừng theo dõi mạng");
        } catch (Exception e) {
            Log.e(TAG, "Không thể dừng NetWorkManager", e);
        }
    }
}
