#!/bin/sh
# Idempotent local object-storage bootstrap.
#
# Run as a one-shot compose job. Kept as a file rather than an inline entrypoint because
# multi-line shell inside a YAML folded scalar gets re-joined unpredictably, and a CORS policy
# that fails to apply is an upload failure that only shows up in a browser console.

set -eu

ENDPOINT="${S3_ENDPOINT:-http://localstack:4566}"
BUCKET="${S3_BUCKET:-shortly-videos-bucket}"
CORS_FILE="${CORS_FILE:-/cors.json}"

echo "Ensuring bucket ${BUCKET} exists at ${ENDPOINT}"
if aws --endpoint-url="${ENDPOINT}" s3 mb "s3://${BUCKET}" 2>/dev/null; then
    echo "Bucket created"
else
    echo "Bucket already exists"
fi

echo "Applying CORS policy from ${CORS_FILE}"
aws --endpoint-url="${ENDPOINT}" s3api put-bucket-cors \
    --bucket "${BUCKET}" \
    --cors-configuration "file://${CORS_FILE}"

echo "Verifying"
aws --endpoint-url="${ENDPOINT}" s3api get-bucket-cors --bucket "${BUCKET}" >/dev/null
aws --endpoint-url="${ENDPOINT}" s3 ls

echo "bucket-ready"
