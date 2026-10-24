import {test,expect} from '@playwright/test';

async function mockSafeApi(page){
 await page.route('**/api/v1/**',async route=>{
  const req=route.request();
  const u=new URL(req.url());
  const path=u.pathname;
  const body=(data,status=200)=>route.fulfill({
   status,contentType:'application/json',body:JSON.stringify(data)
  });
  // A synthetic browser fixture. No live credentials or production SQL.
  if(path==='/api/v1/auth/login')return body({
   token:'test-only-browser-fixture',mustChangePassword:false
  });
  if(path==='/api/v1/me')return body({
   username:'ui-test',displayName:'界面验收账号',mustChangePassword:false
  });
  if(path==='/api/v1/operations/summary')return body({
   deniedRequests:0,pendingApprovals:0,openAlerts:0,sqlExecutionEnabled:false
  });
  if(path==='/api/v1/admin/users')return body([{
   id:'11111111-1111-4111-8111-111111111111',
   username:'ui-test',displayName:'界面验收账号',status:'ACTIVE'
  }]);
  if(path==='/api/v1/admin/roles')return body([]);
  if(path==='/api/v1/auth/logout')return body({});
  if(req.method()!=='GET')return body({message:'fixture is read only'},403);
  return body([]);
 });
}
async function signIn(page){
 await mockSafeApi(page);
 await page.goto('/');
 await expect(page.locator('.auth-login-heading')).toBeVisible();
 await page.locator('input[autocomplete="username"]').fill('ui-test');
 await page.locator('input[autocomplete="current-password"]').fill('Synthetic-Test-Only!Aa9');
 await page.locator('.auth-form button[type="submit"]').click();
 await expect(page.locator('.shell:not(.auth-shell)')).toBeVisible();
}
async function noHorizontalOverflow(page){
 const overflow=await page.evaluate(()=>({
  documentWidth:document.documentElement.scrollWidth,
  viewportWidth:window.innerWidth
 }));
 expect(overflow.documentWidth,
   'Document scrollWidth must fit the viewport').toBeLessThanOrEqual(
     overflow.viewportWidth+2);
}
test('login renders new DataGuard logo without horizontal scrolling at mobile and desktop',async({page})=>{
 for(const width of [390,768,1366]){
  await page.setViewportSize({width,height:820});
  await page.goto('/');
  await expect(page.locator('.auth-login-heading')).toBeVisible();
  await expect(page.locator('.auth-layout img[src="/dataguard-mark.svg"]:visible').first()).toBeVisible();
  await noHorizontalOverflow(page);
 }
});
test('desktop management navigation retains compact banner, audit and identity controls',async({page})=>{
 await page.setViewportSize({width:1366,height:900});
 await signIn(page);
 await expect(page.locator('.sidebar .brand-mark')).toBeVisible();
 for(const item of ['Agent 管理','数据源管理','SQL 安全规则','Flowable 审批','SQL 审计与监控','用户与权限']){
  await page.locator('.sidebar button.nav').filter({hasText:item}).click();
  await expect(page.locator('.shell.compact-page')).toBeVisible();
  const topBarHeight=await page.locator('.bar').evaluate(node=>parseFloat(getComputedStyle(node).height));
  expect(topBarHeight).toBe(46);
  await noHorizontalOverflow(page);
 }
 await page.getByRole('button',{name:'新增用户'}).click();
 await expect(page.getByText('创建内置用户')).toBeVisible();
 await page.getByRole('button',{name:'返回'}).last().click();
 await expect(page.getByText('创建内置用户')).not.toBeVisible();
});
test('mobile console has a compact sidebar and account entry',async({page})=>{
 await page.setViewportSize({width:390,height:844});
 await signIn(page);
 const brand=page.locator('.sidebar .brand-mark');
 await expect(brand).toBeVisible();
 const width=await page.locator('.sidebar').evaluate(node=>Math.round(node.getBoundingClientRect().width));
 expect(width).toBe(64);
 await expect(page.locator('.account-trigger')).toBeVisible();
 await noHorizontalOverflow(page);
});
