import argparse

from sqlalchemy import inspect, text
from sqlalchemy.exc import SQLAlchemyError

from app.models.wayang import WayangTopicOwner


LEGACY_COLUMNS = {"topic", "deployment_id"}
SCOPED_COLUMNS = LEGACY_COLUMNS | {"scope", "fragment", "cluster", "incarnation"}
TABLES = ("wayang_deployments", "wayang_topic_owners", "wayang_continuous", "wayang_cycles",
          "wayang_publication", "wayang_switches")


def check_schema(connection):
    inspector = inspect(connection)
    tables = set(inspector.get_table_names())
    if "wayang_topic_owners" not in tables:
        if tables.intersection(TABLES):
            raise RuntimeError("The Wayang ownership schema requires an explicit offline upgrade.")
        return
    columns = {column["name"] for column in inspector.get_columns("wayang_topic_owners")}
    keys = inspector.get_pk_constraint("wayang_topic_owners")["constrained_columns"]
    checks = {check["name"] for check in inspector.get_check_constraints("wayang_topic_owners")}
    if columns != SCOPED_COLUMNS or set(keys) != {"scope", "fragment", "cluster", "incarnation", "topic"} or "ck_wayang_owner_scope" not in checks:
        raise RuntimeError("The Wayang ownership schema requires an explicit offline upgrade.")


def upgrade(engine):
    with engine.begin() as connection:
        if connection.dialect.name not in ("postgresql", "sqlite"):
            raise RuntimeError("The Wayang ownership upgrade requires PostgreSQL or SQLite.")
        inspector = inspect(connection)
        if not set(TABLES).issubset(inspector.get_table_names()):
            raise RuntimeError("The Wayang journal is missing required history tables.")
        if connection.dialect.name == "postgresql":
            connection.execute(text("LOCK TABLE " + ", ".join(TABLES) + " IN ACCESS EXCLUSIVE MODE"))
        else:
            connection.execute(text("UPDATE wayang_topic_owners SET deployment_id = deployment_id WHERE 0"))
        columns = {column["name"] for column in inspector.get_columns("wayang_topic_owners")}
        if columns == SCOPED_COLUMNS:
            check_schema(connection)
            return False
        keys = inspector.get_pk_constraint("wayang_topic_owners")["constrained_columns"]
        if columns != LEGACY_COLUMNS or keys != ["topic"]:
            raise RuntimeError("The Wayang ownership schema is unknown or partially upgraded.")
        unsafe = (
            "SELECT 1 FROM wayang_topic_owners LIMIT 1",
            "SELECT 1 FROM wayang_deployments WHERE state NOT IN ('completed','failed','stopped','replaced') LIMIT 1",
            "SELECT 1 FROM wayang_cycles WHERE state NOT IN ('completed','failed') LIMIT 1",
            "SELECT 1 FROM wayang_publication WHERE retired = false LIMIT 1",
            "SELECT 1 FROM wayang_switches WHERE state = 'pending' LIMIT 1",
        )
        for query in unsafe:
            if connection.execute(text(query)).first() is not None:
                raise RuntimeError("The Wayang journal has active or ambiguous legacy ownership; upgrade refused.")
        for table in TABLES[2:]:
            if connection.execute(text(
                    f"SELECT 1 FROM {table} h LEFT JOIN wayang_deployments d ON d.id = h.deployment_id "
                    "WHERE d.id IS NULL LIMIT 1")).first() is not None:
                raise RuntimeError("The Wayang journal has orphan history; upgrade refused.")
        connection.execute(text("DROP TABLE wayang_topic_owners"))
        WayangTopicOwner.__table__.create(connection)
        check_schema(connection)
        return True


def main():
    parser = argparse.ArgumentParser(description="Upgrade an offline Wayang ownership journal without changing history.")
    parser.add_argument("--offline", action="store_true", required=True,
                        help="Confirm that the API and Wayang coordinator are stopped.")
    parser.parse_args()
    from app.db.session import sync_engine
    try:
        changed = upgrade(sync_engine)
    except RuntimeError as error:
        parser.exit(1, str(error) + "\n")
    except SQLAlchemyError:
        parser.exit(1, "Wayang ownership upgrade refused; inspect the offline journal without modifying its history.\n")
    print("Wayang ownership schema upgraded." if changed else "Wayang ownership schema is current.")


if __name__ == "__main__":
    main()
