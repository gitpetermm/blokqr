# Image officielle Playwright : embarque Chromium et ses dépendances système,
# nécessaires à l'analyse dynamique. Utiliser python:3.12-slim si l'analyse
# dynamique n'est pas requise (image bien plus légère).
FROM mcr.microsoft.com/playwright/python:v1.49.1-noble

# Utilisateur non-root (durcissement conteneur).
ARG APP_USER=blokqr
RUN useradd --create-home --shell /usr/sbin/nologin ${APP_USER}

WORKDIR /app

# Dépendances Python d'abord (cache des couches).
COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# Code applicatif.
COPY app ./app
COPY scripts ./scripts

# Permissions minimales.
RUN chown -R ${APP_USER}:${APP_USER} /app
USER ${APP_USER}

EXPOSE 8000

# Sonde de vivacité.
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/health').status==200 else 1)"

# 2 workers ; ajuster selon la charge. Pas de --reload en production.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
