# ---------- etapa 1: build del bundle ----------
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build -- --configuration=production

# ---------- etapa 2: servidor estatico ----------
# Una SPA compilada son archivos estaticos: no necesita Node en produccion.
FROM nginx:1.27-alpine
COPY --from=build /app/dist/frontend-pedidos360/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
