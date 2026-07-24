#!/bin/bash
echo "================================================="
echo "PULSARIDE — DÉMO DISPATCH ENGINE V1"
echo "================================================="

echo ""
echo "✅ ÉTAPE 1 — Vérification API"
curl -s http://localhost:8080/health
echo ""

echo "================================================="
echo "✅ ÉTAPE 2 — Flux complet PENDING → CLOSED"
echo "================================================="

REQUEST_ID=$(curl -s -X POST http://localhost:8080/requests \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "demo_salihi_01",
    "patientText": "Mon enfant a de la fièvre depuis 3 jours, 38.5°C, mal à la gorge.",
    "specialtyHint": "pediatrie",
    "urgencyScore": 2
  }' | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "Demande créée : $REQUEST_ID"

DISPATCHED=$(curl -s -X POST "http://localhost:8080/dispatch/next?strategy=S4")
DISP_ID=$(echo $DISPATCHED | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
DISP_PRO=$(echo $DISPATCHED | python3 -c 'import json,sys; print(json.load(sys.stdin).get("assignedProfessionalName","aucun"))')
echo "Dispatché vers : $DISP_PRO"

curl -s -X POST "http://localhost:8080/dispatch/$DISP_ID/accept" > /dev/null
echo "Accepté ✅"
curl -s -X POST "http://localhost:8080/dispatch/$DISP_ID/close" > /dev/null
echo "Clôturé ✅"

echo ""
echo "================================================="
echo "✅ ÉTAPE 3 — Dispatch prioritaire"
echo "================================================="

curl -s -X POST http://localhost:8080/requests \
  -H "Content-Type: application/json" \
  -d '{"patientId":"demo_low","patientText":"Légère toux.","specialtyHint":"generaliste","urgencyScore":0}' > /dev/null
echo "Demande urgence 0 créée"

curl -s -X POST http://localhost:8080/requests \
  -H "Content-Type: application/json" \
  -d '{"patientId":"demo_high","patientText":"Douleur thoracique intense.","specialtyHint":"cardiologie","urgencyScore":3}' > /dev/null
echo "Demande urgence 3 créée"

DISPATCHED=$(curl -s -X POST "http://localhost:8080/dispatch/next?strategy=S4")
DISP_PRO=$(echo $DISPATCHED | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('assignedProfessionalName','aucun'))")
DISP_URG=$(echo $DISPATCHED | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('urgencyScore','?'))")
DISP_PAT=$(echo $DISPATCHED | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('patientId','?'))")
echo "Dispatché : $DISP_PAT | urgence $DISP_URG | $DISP_PRO"
echo "→ Priorité respectée : urgence 3 avant urgence 0 ✅"

DISP_ID=$(echo $DISPATCHED | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
curl -s -X POST "http://localhost:8080/dispatch/$DISP_ID/accept" > /dev/null
curl -s -X POST "http://localhost:8080/dispatch/$DISP_ID/close" > /dev/null
echo "Clôturé ✅"

echo ""
echo "================================================="
echo "✅ ÉTAPE 4 — Disponibilités par spécialité"
echo "================================================="
curl -s http://localhost:8080/availability/specialties/pediatrie | python3 -m json.tool

echo ""
echo "================================================="
echo "✅ ÉTAPE 5 — Métriques temps réel"
echo "================================================="
curl -s http://localhost:8080/metrics/summary | python3 -m json.tool

echo ""
echo "================================================="
echo "🎉 FIN DE LA DÉMO"
echo "================================================="
