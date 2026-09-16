import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { AuthService } from '../../core/auth.service';
import { Role } from '../../core/models';

/**
 * Guia 1.3.2 — pantalla de login publica con el boton "Iniciar sesión con Microsoft".
 *
 * En modo demo la misma pantalla ofrece los cuatro perfiles del Caso 0 para poder
 * mostrar en vivo como cambia la aplicacion segun el rol.
 */
@Component({
  selector: 'app-login',
  imports: [FormsModule],
  templateUrl: './login.html',
  styleUrl: './login.css',
})
export class LoginPage {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly auth = inject(AuthService);

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  readonly demoUsers: { username: string; roles: Role[]; label: string; description: string }[] = [
    {
      username: 'admin@pedidos360.cl',
      roles: ['ADMIN'],
      label: 'Administrador',
      description: 'Catálogo, KPIs, auditoría y todos los pedidos',
    },
    {
      username: 'operador@pedidos360.cl',
      roles: ['OPERATOR'],
      label: 'Operador',
      description: 'Acepta, prepara y despacha pedidos',
    },
    {
      username: 'cliente@pedidos360.cl',
      roles: ['CUSTOMER'],
      label: 'Cliente',
      description: 'Crea pedidos y sigue solo los suyos',
    },
    {
      username: 'auditor@pedidos360.cl',
      roles: ['AUDITOR'],
      label: 'Auditor',
      description: 'Solo lectura sobre el timeline de eventos',
    },
  ];

  loginWithMicrosoft(): void {
    this.auth.loginWithMicrosoft();
  }

  async loginAs(username: string, roles: Role[]): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      await this.auth.loginAsDemoUser(username, roles);
      const redirect = this.route.snapshot.queryParamMap.get('redirect') ?? '/dashboard';
      await this.router.navigateByUrl(redirect);
    } catch (err) {
      this.error.set(
        'No se pudo obtener el token de demo. Verifica que ms-pedidos360-bff esté corriendo en ' +
          'el puerto 8080 con el perfil dev activo.',
      );
    } finally {
      this.loading.set(false);
    }
  }
}
