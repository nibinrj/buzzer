/**
 * Event records exchanged between Buzzer services over Kafka.
 *
 * <p>This module holds event records only: no entities, no Spring beans, no framework
 * dependencies. Producers and consumers both depend on it so the event contract lives in
 * one place.
 */
package dev.nibin.buzzer.events;
