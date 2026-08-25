package com.bnm.diagnosis.api

object Constants {
    /**
     * Supabase Edge Functions base URL (India project).
     *
     * BNMAdmin talks ONLY to Supabase — never to the studio.bnmapp.com Next.js
     * app. All admin-* edge functions are served from this base URL; auth login,
     * AI scribe, clinical, chat, commerce, etc. all resolve here.
     *
     * If you need to point at the global/dev project (`hgckppnisshuwsfbhbbb`),
     * change this constant — do NOT reintroduce a Next.js fallback URL.
     */
    const val EDGE_FUNCTIONS_BASE_URL = "https://voelldnyfamrbzvthgfk.supabase.co/functions/v1"

    /**
     * BNM Delivery Partner SaaS — direct edge function base URL.
     *
     * For the delivery feature (rider workflows + collected items), BNMAdmin
     * talks DIRECTLY to the SaaS using the tenant `api_key` retrieved from
     * business_extensions.config. The platform secret is NEVER embedded in
     * BNMAdmin — every call uses `x-api-key`, not `x-bnm-secret`.
     */
    const val BNM_DELIVERY_PARTNER_BASE_URL = "https://yzjtlimzvnwiyqhaguay.supabase.co/functions/v1"

    /**
     * Supabase anon key for the BNM Delivery Partner SaaS project. Sent as
     * `apikey` + `Authorization: Bearer` for endpoints whose gateway has
     * verify_jwt=true (workflow-crud, dispatch-rule-execute). Safe to embed —
     * it's a public JWT used only for gateway routing. The signing role is
     * still `anon`; tenancy is enforced server-side via `x-api-key`.
     */
    const val BNM_DELIVERY_PARTNER_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Inl6anRsaW16dm53aXlxaGFndWF5Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzM1NjY2MjIsImV4cCI6MjA4OTE0MjYyMn0.rGk1TUFn_KlUFAdKB7HZHrtYGwEWsqrXxjjTQGv4rJI"

    /**
     * Firebase Web Client ID from your Firebase Console.
     * Found under Project Settings > General > Your apps > Web app > OAuth 2.0 Client IDs
     */
    const val GOOGLE_WEB_CLIENT_ID = "YOUR_FIREBASE_WEB_CLIENT_ID.apps.googleusercontent.com"
}
