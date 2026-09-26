#!/bin/sh
# Idempotent local object-storage bootstrap: creates the upload bucket and applies the CORS rule
# the presigned browser PUT needs. Needs the AWS CLI; LocalStack is not a Compose service.

set -eu

ENDPOINT="${S3_ENDPOINT:-http://localhost:4566}"
BUCKET="${S3_BUCKET:-shortly-videos-bucket}"
REPO_ROOT="${REPO_ROOT:-$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)}"
CORS_FILE="${CORS_FILE:-${REPO_ROOT}/docs/s3-cors.json}"

if [ ! -f "${CORS_FILE}" ]; then
    echo "CORS policy not found at ${CORS_FILE}" >&2
    exit 1
fi

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
