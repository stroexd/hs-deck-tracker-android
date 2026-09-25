package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.cards.CardDatabase

/** Kleine Kartendatenbank im HearthstoneJSON-Format (inkl. unbekannter Felder). */
object TestCards {
    const val FIREBALL = 1
    const val ARCANE_MISSILES = 2
    const val LEEROY = 3
    const val EPIC_MAGE = 4
    const val RARE_NEUTRAL = 5
    const val JAINA = 637
    const val REWARD_LEGENDARY = 7
    const val DUAL_CLASS = 8
    const val BASIC = 9
    const val RENATHAL = 10
    const val OLD_WILD = 11

    val json = """
        [
          {"dbfId":1,"id":"CS2_029","name":"Feuerball","cost":4,"type":"SPELL","rarity":"COMMON","cardClass":"MAGE","set":"EXPERT1","collectible":true,"text":"Verursacht ${'$'}6 Schaden.","spellSchool":"FIRE","referencedTags":["X"],"elite":false},
          {"dbfId":2,"id":"EX1_277","name":"Arkane Geschosse","cost":1,"type":"SPELL","rarity":"COMMON","cardClass":"MAGE","set":"CORE","collectible":true,"text":"<b>Test</b> [x]Text"},
          {"dbfId":3,"id":"LEG_001","name":"Leeroy Jenkins","cost":5,"attack":6,"health":2,"type":"MINION","rarity":"LEGENDARY","cardClass":"NEUTRAL","set":"TIME_TRAVEL","collectible":true,"mechanics":["CHARGE","BATTLECRY"]},
          {"dbfId":4,"id":"EPIC_01","name":"Epischer Zauber","cost":6,"type":"SPELL","rarity":"EPIC","cardClass":"MAGE","set":"THE_LOST_CITY","collectible":true},
          {"dbfId":5,"id":"RARE_01","name":"Seltener Diener","cost":2,"attack":2,"health":3,"type":"MINION","rarity":"RARE","cardClass":"NEUTRAL","set":"TIME_TRAVEL","collectible":true,"races":["BEAST","MURLOC"]},
          {"dbfId":637,"id":"HERO_08","name":"Jaina Prachtmeer","type":"HERO","rarity":"FREE","cardClass":"MAGE","set":"HERO_SKINS","collectible":true},
          {"dbfId":7,"id":"REWARD_01","name":"Belohnungskarte","cost":3,"type":"MINION","rarity":"LEGENDARY","cardClass":"NEUTRAL","set":"TIME_TRAVEL","collectible":true,"howToEarn":"Belohnungspfad"},
          {"dbfId":8,"id":"DUAL_01","name":"Doppelklasse","cost":3,"type":"MINION","rarity":"RARE","cardClass":"MAGE","classes":["MAGE","ROGUE"],"set":"SCHOLOMANCE","collectible":true},
          {"dbfId":9,"id":"BASIC_01","name":"Basiskarte","cost":0,"type":"SPELL","rarity":"FREE","cardClass":"MAGE","set":"LEGACY","collectible":true},
          {"dbfId":10,"id":"REV_018","name":"Prinz Renathal","cost":3,"type":"MINION","rarity":"LEGENDARY","cardClass":"NEUTRAL","set":"REVENDRETH","collectible":true},
          {"dbfId":11,"id":"OLD_01","name":"Alte Karte","cost":7,"type":"MINION","rarity":"COMMON","cardClass":"NEUTRAL","set":"NAXX","collectible":true},
          {"dbfId":12,"id":"GAME_005","name":"Die Münze","cost":0,"type":"SPELL","cardClass":"NEUTRAL","set":"CORE","collectible":false}
        ]
    """.trimIndent()

    val db: CardDatabase by lazy { CardDatabase.parse(json) }
}
