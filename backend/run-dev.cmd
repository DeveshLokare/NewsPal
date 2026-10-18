@echo off
rem Runs the backend locally with the dev profile (CORS for localhost, dev JWT secret).
rem Start Postgres first: docker compose up -d
cd /d "%~dp0"
call "%~dp0mvnw.cmd" -q spring-boot:run -Dspring-boot.run.profiles=dev
