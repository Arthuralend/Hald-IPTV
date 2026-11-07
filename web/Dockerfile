FROM node:22-alpine
WORKDIR /app
COPY server.js ./
COPY public ./public
ENV PORT=8090
EXPOSE 8090
USER node
CMD ["node", "server.js"]
