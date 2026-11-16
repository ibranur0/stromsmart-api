# Setter variablene appen leser, ut fra .env.local som "neon deploy" lager.
#
#   source sky/neon-env.sh
#   SPRING_PROFILES_ACTIVE=cloud ./mvnw spring-boot:run
#
# Appen bruker adressen med -pooler. Flyway bruker den direkte adressen.
env_file=${1:-.env.local}
[ -f "$env_file" ] || { echo "Fant ikke $env_file. Kjør 'neon deploy' først." >&2; return 1 2>/dev/null || exit 1; }

neon_value() { grep -E "^$1=" "$env_file" | head -1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//'; }
# postgresql://bruker:passord@vert/database?...  ->  jdbc:postgresql://vert/database?sslmode=require
neon_jdbc() { local rest=${1#*://}; rest=${rest#*@}; echo "jdbc:postgresql://${rest%%\?*}?sslmode=require"; }

pooled=$(neon_value DATABASE_URL)
direct=$(neon_value DATABASE_URL_UNPOOLED)
userinfo=${pooled#*://}; userinfo=${userinfo%%@*}

export SPRING_DATASOURCE_URL=$(neon_jdbc "$pooled")
export FLYWAY_URL=$(neon_jdbc "$direct")
export SPRING_DATASOURCE_USERNAME=${userinfo%%:*}
export SPRING_DATASOURCE_PASSWORD=${userinfo#*:}
unset pooled direct userinfo env_file
