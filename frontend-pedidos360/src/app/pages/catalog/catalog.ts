import { CurrencyPipe, DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ApiService } from '../../core/api.service';
import { AuthService } from '../../core/auth.service';
import { Product, ProductRequest } from '../../core/models';
import { describeApiError } from '../../shared/api-error';

/**
 * Caso 0 — /catalog: CatalogComponent con ProductCardComponent y ProductFormComponent.
 *
 * El Operador entra (necesita ver stock para operar) pero no puede escribir: el
 * formulario ni se dibuja, y si lo forzara, el backend responde 403 porque
 * POST/PUT/DELETE de /api/catalog estan anotados con hasRole('ADMIN').
 */
@Component({
  selector: 'app-catalog',
  imports: [FormsModule, CurrencyPipe, DatePipe],
  templateUrl: './catalog.html',
  styleUrl: './catalog.css',
})
export class CatalogPage {
  private readonly api = inject(ApiService);
  readonly auth = inject(AuthService);

  readonly products = signal<Product[]>([]);
  readonly loading = signal(true);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly info = signal<string | null>(null);
  readonly showInactive = signal(false);

  readonly editing = signal<Product | null>(null);
  readonly showForm = signal(false);
  readonly form = signal<ProductRequest>(this.emptyForm());

  readonly canWrite = computed(() => this.auth.hasRole('ADMIN'));

  readonly lowStock = computed(() => this.products().filter((p) => p.active && p.stock <= 10));

  constructor() {
    this.load();
  }

  private emptyForm(): ProductRequest {
    return { sku: '', name: '', description: '', price: 0, stock: 0, active: true };
  }

  load(): void {
    this.loading.set(true);
    this.api.listProducts(!this.showInactive()).subscribe({
      next: (products) => {
        this.products.set(products);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(describeApiError(err));
        this.loading.set(false);
      },
    });
  }

  toggleInactive(): void {
    this.showInactive.set(!this.showInactive());
    this.load();
  }

  startCreate(): void {
    this.editing.set(null);
    this.form.set(this.emptyForm());
    this.showForm.set(true);
  }

  startEdit(product: Product): void {
    this.editing.set(product);
    this.form.set({
      sku: product.sku,
      name: product.name,
      description: product.description ?? '',
      price: product.price,
      stock: product.stock,
      active: product.active,
    });
    this.showForm.set(true);
  }

  patch(field: keyof ProductRequest, value: unknown): void {
    this.form.update((current) => ({ ...current, [field]: value }) as ProductRequest);
  }

  save(): void {
    const body = this.form();
    if (!body.sku || !body.name) {
      this.error.set('SKU y nombre son obligatorios.');
      return;
    }
    this.busy.set(true);
    const current = this.editing();
    const request = current
      ? this.api.updateProduct(current.id, body)
      : this.api.createProduct(body);

    request.subscribe({
      next: (product) => {
        this.info.set(`Producto ${product.sku} ${current ? 'actualizado' : 'creado'}.`);
        this.error.set(null);
        this.showForm.set(false);
        this.busy.set(false);
        this.load();
      },
      error: (err) => {
        this.error.set(describeApiError(err));
        this.busy.set(false);
      },
    });
  }

  /** Baja lógica: los pedidos históricos siguen apuntando al producto. */
  deactivate(product: Product): void {
    this.busy.set(true);
    this.api.deactivateProduct(product.id).subscribe({
      next: () => {
        this.info.set(`Producto ${product.sku} desactivado.`);
        this.busy.set(false);
        this.load();
      },
      error: (err) => {
        this.error.set(describeApiError(err));
        this.busy.set(false);
      },
    });
  }
}
