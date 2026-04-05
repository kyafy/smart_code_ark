FROM node:20-alpine AS build
WORKDIR /app/frontend-web
COPY frontend-web/package.json frontend-web/package-lock.json* ./
RUN npm ci
COPY frontend-web/ ./
COPY packages/ /app/packages/
RUN npm run build

FROM nginx:1.25-alpine
COPY --from=build /app/frontend-web/dist /usr/share/nginx/html
COPY frontend-web/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
