# !/bin/sh Idempotent S3 bucket bootstrap:

set -eu

BUCKET="${S3_BUCKET:-shortly-videos-bucket}"
REGION="${AWS_REGION:-us-east-1}"
REPO_ROOT="${REPO_ROOT:-$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)}"
CORS_FILE="${CORS_FILE:-${REPO_ROOT}/docs/s3-cors.json}"

if [ ! -f "${CORS_FILE}" ]; then
    echo "CORS policy not found at ${CORS_FILE}" >&2
    exit 1
fi

echo "Ensuring bucket ${BUCKET} exists in ${REGION}"
if aws s3api head-bucket --bucket "${BUCKET}" --region "${REGION}" 2>/dev/null; then
    echo "Bucket already exists"
else
    aws s3api create-bucket --bucket "${BUCKET}" --region "${REGION}" \
        --create-bucket-configuration "LocationConstraint=${REGION}"
    echo "Bucket created"
fi

echo "Applying CORS policy from ${CORS_FILE}"
aws s3api put-bucket-cors \
    --bucket "${BUCKET}" \
    --region "${REGION}" \
    --cors-configuration "file://${CORS_FILE}"

echo "Verifying"
aws s3api get-bucket-cors --bucket "${BUCKET}" --region "${REGION}" >/dev/null
aws s3 ls

echo "bucket-ready"
