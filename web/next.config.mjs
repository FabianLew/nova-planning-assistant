/**
 * Production: static export (out/) served by Spring Boot from the same origin, so cookies and CSRF stay simple.
 * Development: `next dev` proxies /api to the local Spring Boot on :8080.
 */
const isDev = process.env.NODE_ENV === 'development';

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  trailingSlash: true,
  images: { unoptimized: true },
  ...(isDev
    ? {
        async rewrites() {
          const api = process.env.NOVA_API_URL || 'http://localhost:8080';
          return [{ source: '/api/:path*', destination: `${api}/api/:path*` }];
        },
      }
    : { output: 'export' }),
};

export default nextConfig;
