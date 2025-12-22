#!/bin/bash

# ============================================================================
# Sentinel 开发环境启动脚本
# ============================================================================

set -e

echo "========================================="
echo "  Sentinel 开发环境启动"
echo "========================================="

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 检查 Docker 是否运行
if ! docker info > /dev/null 2>&1; then
    echo -e "${RED}错误: Docker 未运行，请先启动 Docker${NC}"
    exit 1
fi

# 检查 .env 文件是否存在
if [ ! -f .env ]; then
    echo -e "${YELLOW}警告: .env 文件不存在，将使用默认配置${NC}"
    echo -e "${YELLOW}建议: 复制 .env.example 为 .env 并配置${NC}"
fi

# 启动 Docker Compose 服务
echo -e "${GREEN}[1/4] 启动 Docker 服务（MySQL, Redis, Ollama）...${NC}"
docker-compose up -d

# 等待服务就绪
echo -e "${GREEN}[2/4] 等待服务就绪...${NC}"
echo "等待 MySQL 启动..."
until docker exec sentinel-mysql mysqladmin ping -h localhost --silent 2>/dev/null; do
    echo -n "."
    sleep 2
done
echo -e "${GREEN}MySQL 已就绪${NC}"

echo "等待 Redis 启动..."
until docker exec sentinel-redis redis-cli ping 2>/dev/null | grep -q PONG; do
    echo -n "."
    sleep 1
done
echo -e "${GREEN}Redis 已就绪${NC}"

# 显示服务状态
echo -e "${GREEN}[3/4] 服务状态:${NC}"
docker-compose ps

# 提示如何启动 Spring Boot 应用
echo ""
echo -e "${GREEN}[4/4] Docker 服务已启动完成！${NC}"
echo ""
echo "========================================="
echo "  下一步操作"
echo "========================================="
echo "1. 使用 Maven 启动应用:"
echo "   mvn spring-boot:run"
echo ""
echo "2. 或使用 IDE 运行 SentinelApplication"
echo ""
echo "3. 访问应用:"
echo "   - 应用地址: http://localhost:8080"
echo "   - Swagger UI: http://localhost:8080/swagger-ui.html"
echo "   - Actuator: http://localhost:8080/actuator"
echo ""
echo "4. 查看日志:"
echo "   docker-compose logs -f"
echo ""
echo "5. 停止服务:"
echo "   ./stop-dev.sh"
echo "========================================="
