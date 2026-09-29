#!/bin/bash

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}Fetching access token from Keycloak...${NC}"

# Fetch the token
TOKEN_RESPONSE=$(curl -s --location 'https://digit-lts.digit.org/keycloak/realms/MAD/protocol/openid-connect/token' \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=password' \
  --data-urlencode 'client_id=auth-server' \
  --data-urlencode 'client_secret=changeme' \
  --data-urlencode 'username=test@example.com' \
  --data-urlencode 'password=default@123')

# Extract access token using grep and sed (works without jq)
ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | grep -o '"access_token":"[^"]*"' | sed 's/"access_token":"\(.*\)"/\1/')

if [ -z "$ACCESS_TOKEN" ]; then
  echo -e "${RED}Failed to retrieve access token${NC}"
  echo "Response: $TOKEN_RESPONSE"
  exit 1
fi

echo -e "${GREEN}Token retrieved successfully${NC}"
echo -e "${YELLOW}Token (first 50 chars): ${ACCESS_TOKEN:0:50}...${NC}"

echo -e "\n${YELLOW}Running conformance tests...${NC}\n"

# Run the conformance test with pytest
cd boundary-service
python3 -m pytest -v \
  --base-url=https://digit-lts.digit.org/boundary-java/v3 \
  --api-token="$ACCESS_TOKEN" \
  --tenant-id=EO6

TEST_EXIT_CODE=$?

if [ $TEST_EXIT_CODE -eq 0 ]; then
  echo -e "\n${GREEN}Conformance tests completed successfully${NC}"
else
  echo -e "\n${RED}Conformance tests failed with exit code: $TEST_EXIT_CODE${NC}"
fi

exit $TEST_EXIT_CODE
