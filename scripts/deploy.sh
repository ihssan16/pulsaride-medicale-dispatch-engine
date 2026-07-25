#!/bin/bash
echo "================================================="
echo "PULSARIDE — Script de déploiement V1"
echo "================================================="

# 1. Installer les dépendances
echo "📦 Installation des dépendances..."
sudo apt update -q
sudo apt install -y docker.io git openjdk-21-jdk maven python3-pip

# 2. Démarrer Docker
sudo systemctl start docker
sudo usermod -aG docker $USER

# 3. Installer les librairies Python
echo "🐍 Installation des librairies Python..."
pip3 install requests matplotlib --break-system-packages

# 4. Compiler le backend
echo "🔨 Compilation du backend..."
cd backend
mvn clean package -DskipTests -q
cd ..

# 5. Lancer Docker Compose
echo "🚀 Lancement des services..."
docker compose up --build -d

# 6. Attendre que l'API soit prête
echo "⏳ Attente démarrage API..."
sleep 15

# 7. Vérifier
echo ""
echo "✅ Vérification..."
curl -s http://localhost:8080/health
echo ""
echo "================================================="
echo "🎉 Déploiement terminé !"
echo "   API disponible sur http://localhost:8080"
echo "   Health : http://localhost:8080/health"
echo "   Métriques : http://localhost:8080/metrics/summary"
echo "================================================="
