package com.firebox.core;

import com.firebox.core.ClientAccessRecord;
import com.firebox.core.ConnectionInfo;
import com.firebox.core.ProviderCreateRequest;
import com.firebox.core.ProviderInfo;
import com.firebox.core.ProviderUpdateRequest;
import com.firebox.core.RouteInfo;
import com.firebox.core.RouteWriteRequest;
import com.firebox.core.StatsResponse;

interface IControlService {
    String Ping(in String message);

    void Shutdown();

    int GetVersionCode();

    StatsResponse GetDailyStats(int year, int month, int day);

    StatsResponse GetMonthlyStats(int year, int month);

    List<ProviderInfo> ListProviders();

    int AddProvider(in ProviderCreateRequest request);

    void UpdateProvider(in ProviderUpdateRequest request);

    void DeleteProvider(int providerId);

    List<String> FetchProviderModels(int providerId);

    List<RouteInfo> ListRoutes();

    int AddRoute(in RouteWriteRequest request);

    void UpdateRoute(in RouteWriteRequest request);

    void DeleteRoute(int routeId);

    List<ConnectionInfo> ListConnections();

    List<ClientAccessRecord> ListClientAccess();

    void UpdateClientAccessAllowed(int accessId, boolean isAllowed);
}
