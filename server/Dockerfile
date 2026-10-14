FROM node:22-alpine
WORKDIR /app
COPY package.json ioniq6.js server.js install.sh ./
COPY lib ./lib
RUN mkdir -p /data && chown node:node /data
USER node
ENV NODE_ENV=production DATA_DIR=/data PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=60s --timeout=5s CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1
CMD ["node", "server.js"]
