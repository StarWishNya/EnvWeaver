#!/bin/bash

# Sentinel 项目快速测试脚本

echo "🧪 Sentinel 项目快速测试"
echo "=========================="
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 测试计数
PASSED=0
FAILED=0

# 测试函数
test_command() {
    local test_name=$1
    local command=$2
    
    echo -n "测试: $test_name ... "
    
    if eval "$command" > /dev/null 2>&1; then
        echo -e "${GREEN}✓ 通过${NC}"
        ((PASSED++))
        return 0
    else
        echo -e "${RED}✗ 失败${NC}"
        ((FAILED++))
        return 1
    fi
}

# 1. 检查必需软件
echo "📋 检查必需软件"
echo "---"
test_command "Java 17+" "java -version 2>&1 | grep -E 'version \"(17|21)'"
test_command "Maven 3.8+" "mvn -version | grep -E 'Apache Maven 3\.[8-9]|Apache Maven [4-9]'"
test_command "Docker" "docker --version"
test_command "Docker Compose" "docker-compose --version"
echo ""

# 2. 检查项目文件
echo "📁 检查项目文件"
echo "---"
test_command "pom.xml 存在" "test -f pom.xml"
test_command "主启动类存在" "test -f src/main/java/com/sentinel/SentinelApplication.java"
test_command "配置文件存在" "test -f src/main/resources/application.yml"
test_command "Docker Compose 配置存在" "test -f docker-compose.yml"
echo ""

# 3. 检查 Docker 服务
echo "🐳 检查 Docker 服务"
echo "---"
if docker-compose ps | grep -q "sentinel-mysql.*Up"; then
    echo -e "MySQL 服务: ${GREEN}✓ 运行中${NC}"
    ((PASSED++))
else
    echo -e "MySQL 服务: ${YELLOW}⚠ 未运行${NC}"
    echo "  提示: 运行 ./start-dev.sh 启动服务"
fi

if docker-compose ps | grep -q "sentinel-redis.*Up"; then
    echo -e "Redis 服务: ${GREEN}✓ 运行中${NC}"
    ((PASSED++))
else
    echo -e "Redis 服务: ${YELLOW}⚠ 未运行${NC}"
    echo "  提示: 运行 ./start-dev.sh 启动服务"
fi
echo ""

# 4. 编译项目
echo "🔨 编译项目"
echo "---"
echo "正在编译项目（跳过测试）..."
if mvn clean compile -DskipTests > /tmp/maven-compile.log 2>&1; then
    echo -e "${GREEN}✓ 编译成功${NC}"
    ((PASSED++))
else
    echo -e "${RED}✗ 编译失败${NC}"
    echo "查看详细日志: cat /tmp/maven-compile.log"
    ((FAILED++))
fi
echo ""

# 5. 检查端口占用
echo "🔌 检查端口占用"
echo "---"
if lsof -i:8080 > /dev/null 2>&1; then
    echo -e "端口 8080: ${YELLOW}⚠ 已被占用${NC}"
    lsof -i:8080 | grep LISTEN
else
    echo -e "端口 8080: ${GREEN}✓ 可用${NC}"
    ((PASSED++))
fi
echo ""

# 6. 总结
echo "📊 测试总结"
echo "=========================="
echo -e "通过: ${GREEN}${PASSED}${NC}"
echo -e "失败: ${RED}${FAILED}${NC}"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}✅ 所有测试通过！项目可以启动。${NC}"
    echo ""
    echo "🚀 启动应用:"
    echo "  mvn spring-boot:run"
    echo ""
    echo "📖 访问 API 文档:"
    echo "  http://localhost:8080/swagger-ui.html"
    exit 0
else
    echo -e "${RED}❌ 部分测试失败，请检查上述错误。${NC}"
    exit 1
fi
