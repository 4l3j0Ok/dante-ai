#!/bin/bash

echo "Deteniendo servicios docker"
docker compose down
echo "Descargando modelo cargado de LMS"
lms unload --all
