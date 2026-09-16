import { Routes } from '@angular/router';

import { authGuard, roleGuard } from './core/guards';

/**
 * Mapa de rutas del Caso 0 con su control de acceso.
 *
 *   /login     publico
 *   /dashboard cualquier usuario autenticado
 *   /orders    Admin, Operator, Customer (cada uno ve distinto)
 *   /catalog   Admin, Operator (escritura solo Admin, lo valida el backend)
 *   /reports   Admin
 *   /audit     Admin, Auditor
 *
 * loadComponent (lazy loading): cada pantalla viaja en su propio chunk. Un Cliente
 * nunca descarga el codigo de reporteria ni de auditoria.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login').then((m) => m.LoginPage),
  },
  {
    // MSAL vuelve aqui tras el login. El componente solo espera a que
    // handleRedirectPromise() termine y redirige al destino real.
    path: 'auth/callback',
    loadComponent: () => import('./pages/login/callback').then((m) => m.CallbackPage),
  },
  {
    path: 'dashboard',
    canActivate: [authGuard],
    loadComponent: () => import('./pages/dashboard/dashboard').then((m) => m.DashboardPage),
  },
  {
    path: 'orders',
    canActivate: [roleGuard('ADMIN', 'OPERATOR', 'CUSTOMER')],
    loadComponent: () => import('./pages/orders/orders').then((m) => m.OrdersPage),
  },
  {
    path: 'catalog',
    canActivate: [roleGuard('ADMIN', 'OPERATOR')],
    loadComponent: () => import('./pages/catalog/catalog').then((m) => m.CatalogPage),
  },
  {
    path: 'reports',
    canActivate: [roleGuard('ADMIN')],
    loadComponent: () => import('./pages/reports/reports').then((m) => m.ReportsPage),
  },
  {
    path: 'audit',
    canActivate: [roleGuard('ADMIN', 'AUDITOR')],
    loadComponent: () => import('./pages/audit/audit').then((m) => m.AuditPage),
  },
  {
    path: 'forbidden',
    loadComponent: () => import('./pages/forbidden/forbidden').then((m) => m.ForbiddenPage),
  },
  { path: '**', redirectTo: 'dashboard' },
];
