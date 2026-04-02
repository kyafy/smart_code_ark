FROM node:20-alpine

WORKDIR /app
COPY server.js /app/server.js

ENV PORT=19090
ENV JEECG_WORKSPACE_ROOT=/tmp/smartark

EXPOSE 19090

CMD ["node", "/app/server.js"]
