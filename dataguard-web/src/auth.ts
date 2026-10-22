import {UserManager, WebStorageStateStore} from 'oidc-client-ts';
const authority=import.meta.env.VITE_OIDC_AUTHORITY as string | undefined;
const clientId=import.meta.env.VITE_OIDC_CLIENT_ID as string | undefined;
export const configured=Boolean(authority && clientId);
export const auth=configured?new UserManager({
 authority:authority!,
 client_id:clientId!,
 redirect_uri:(import.meta.env.VITE_OIDC_REDIRECT_URI as string|undefined) || window.location.origin + '/auth/callback',
 response_type:'code',
 scope:'openid profile email',
 userStore:new WebStorageStateStore({store:window.sessionStorage})
}):null;
