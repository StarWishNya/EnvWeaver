#!/bin/bash

# ============================================================================
# Sentinel 开发环境停止脚本
# ============================================================================

set -e

# 切换到项目根目录（脚本所在目录的上一级）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_ROOT"

echo "========================================="
echo "  Sentinel 开发环境停止"
echo "========================================="
echo "项目根目录: $PROJECT_ROOT"
echo ""

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 停止 Docker Compose 服务
echo -e "${YELLOW}停止 Docker 服务...${NC}"
docker-compose down

echo ""
echo -e "${GREEN}所有服务已停止${NC}"
echo ""
echo "========================================="
echo "  提示"
echo "========================================="
echo "1. 数据已持久化，下次启动时数据仍然存在"
echo ""
echo "2. 如需完全清理（包括数据卷）:"
echo "   docker-compose down -v"
echo ""
echo "3. 重新启动服务:"
echo "   ./start-dev.sh"
echo "========================================="
