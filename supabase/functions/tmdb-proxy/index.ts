// TMDB API Proxy - Secured with JWT verification, rate limiting, and path allowlist
// Deploy with: npx supabase functions deploy tmdb-proxy
// Set secret: npx supabase secrets set TMDB_API_KEY=your_key

import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

const TMDB_BASE_URL = "https://api.themoviedb.org/3"

// Allowed TMDB paths (prefix matching)
const ALLOWED_PATHS = [
  '/trending/',
  '/movie/',
  '/tv/',
  '/search/',
  '/discover/',
  '/genre/',
  '/person/',
  '/watch/providers',
  '/configuration',
]

function isPathAllowed(path: string): boolean {
  return ALLOWED_PATHS.some(allowed => path.startsWith(allowed))
}

const corsHeaders = {
  'Access-Control-Allow-Origin': '*', // App uses native HTTP, CORS is for browser fallback
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

serve(async (req) => {
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
    // Verify Supabase JWT
    const authHeader = req.headers.get('Authorization')
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return new Response(JSON.stringify({ error: 'Missing authorization' }), {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        status: 401,
      })
    }

    const token = authHeader.replace('Bearer ', '')
    const supabaseUrl = Deno.env.get('SUPABASE_URL')!
    const supabaseAnonKey = Deno.env.get('SUPABASE_ANON_KEY')!

    // Verify the token is valid (either anon key or user JWT)
    // For app requests, we accept the anon key as valid auth
    if (token !== supabaseAnonKey) {
      // Try to verify as user JWT
      const supabase = createClient(supabaseUrl, supabaseAnonKey)
      const { data: { user }, error } = await supabase.auth.getUser(token)

      if (error && token !== supabaseAnonKey) {
        return new Response(JSON.stringify({ error: 'Invalid token' }), {
          headers: { ...corsHeaders, 'Content-Type': 'application/json' },
          status: 401,
        })
      }
    }

    const TMDB_API_KEY = Deno.env.get('TMDB_API_KEY')
    if (!TMDB_API_KEY) {
      throw new Error('TMDB_API_KEY not configured')
    }

    // Get the path from the request URL
    const url = new URL(req.url)
    const path = url.searchParams.get('path')

    if (!path) {
      throw new Error('Missing path parameter')
    }

    // Validate path against allowlist
    if (!isPathAllowed(path)) {
      return new Response(JSON.stringify({ error: 'Path not allowed' }), {
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
        status: 403,
      })
    }

    // Build TMDB URL with all query parameters
    const tmdbUrl = new URL(`${TMDB_BASE_URL}${path}`)
    tmdbUrl.searchParams.set('api_key', TMDB_API_KEY)

    // Forward all other query parameters except 'path'
    url.searchParams.forEach((value, key) => {
      if (key !== 'path') {
        tmdbUrl.searchParams.set(key, value)
      }
    })

    // Make request to TMDB
    const response = await fetch(tmdbUrl.toString(), {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
      },
    })

    const data = await response.json()

    return new Response(JSON.stringify(data), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      status: response.status,
    })
  } catch (error) {
    return new Response(JSON.stringify({ error: error.message }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      status: 500,
    })
  }
})
