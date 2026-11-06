FROM python:3.12-alpine
WORKDIR /app
COPY app.py /app/app.py
RUN addgroup -S app && adduser -S app -G app && mkdir -p /data && chown -R app:app /data
USER app
EXPOSE 8787
CMD ["python", "-u", "/app/app.py"]
