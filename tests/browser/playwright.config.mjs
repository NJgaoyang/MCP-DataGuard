import {defineConfig,devices} from '@playwright/test';

export default defineConfig({
 testDir:'.',
 testMatch:'*.spec.mjs',
 timeout:30000,
 expect:{timeout:8000},
 retries:process.env.CI?1:0,
 workers:1,
 reporter:process.env.CI?'github':'list',
 use:{
  baseURL:'http://127.0.0.1:4173',
  trace:'retain-on-failure',
  screenshot:'only-on-failure',
  ...devices['Desktop Chrome']
 },
 webServer:{
  command:'cd ../../dataguard-web && npm run preview -- --host 127.0.0.1 --port 4173',
  url:'http://127.0.0.1:4173/',
  timeout:45000,
  reuseExistingServer:!process.env.CI
 }
});
