/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.watabou.utils;

import com.watabou.noosa.Game;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class Random {

	//we store a stack of random number generators, which may be seeded deliberately or randomly.
	//top of the stack is what is currently being used to generate new numbers.
	//the base generator is always created with no seed, and cannot be popped.
	//
	//Each pushed generator remembers the thread that pushed it. A thread only ever
	//draws from generators it pushed itself: scoped seeded pushes (level gen, enchant
	//rolls, tile variance) can no longer bleed into concurrent draws from other
	//threads. This matters for LAN lockstep play, where the simulation's draw
	//sequence must be identical on every device.
	private static class GenEntry {
		final java.util.Random gen;
		final Thread owner;
		GenEntry(java.util.Random gen, Thread owner){
			this.gen = gen;
			this.owner = owner;
		}
	}

	private static java.util.Random baseGenerator;
	private static ArrayDeque<GenEntry> generators;
	static {
		resetGenerators();
	}

	// --- LAN lockstep simulation generator ---
	//
	// In LAN multiplayer every device runs the full simulation, so all gameplay RNG
	// (combat rolls, mob AI, respawns) must produce the same sequence on every device.
	// When a sim generator is bound, threads registered as simulation threads (the
	// actor thread and level-transition workers) draw from it instead of the unseeded
	// base generator whenever they have no scoped generator of their own pushed.
	// Render/UI threads keep drawing from the base generator, so cosmetic draws
	// (particles, sound pitch) never disturb the deterministic sequence.
	//
	// The generator's state is a single long, so it can be saved into the game bundle
	// and restored on resume, keeping devices in lockstep across save/load.
	public static class DeterministicRandom extends java.util.Random {
		private long state;
		public DeterministicRandom(long seed){
			this.state = seed;
		}
		@Override
		protected int next(int bits) {
			//SplitMix64: every java.util.Random method funnels through next(bits)
			state += 0x9E3779B97F4A7C15L;
			long z = state;
			z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
			z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
			z = z ^ (z >>> 31);
			return (int)(z >>> (64 - bits));
		}
		public synchronized long getState(){ return state; }
		public synchronized void setState(long state){ this.state = state; }
	}

	private static volatile DeterministicRandom simGenerator = null;
	private static final java.util.Set<Thread> simThreads =
			Collections.synchronizedSet(Collections.newSetFromMap(new java.util.WeakHashMap<Thread, Boolean>()));

	public static synchronized void bindSimGenerator( long seed ){
		simGenerator = new DeterministicRandom( scrambleSeed(seed) );
	}

	public static synchronized void bindSimGeneratorState( long state ){
		simGenerator = new DeterministicRandom( 0 );
		simGenerator.setState( state );
	}

	public static synchronized void unbindSimGenerator(){
		simGenerator = null;
	}

	public static boolean simGeneratorBound(){
		return simGenerator != null;
	}

	public static long getSimGeneratorState(){
		DeterministicRandom gen = simGenerator;
		return gen != null ? gen.getState() : 0;
	}

	public static void registerSimThread(){
		simThreads.add(Thread.currentThread());
	}

	public static void unregisterSimThread(){
		simThreads.remove(Thread.currentThread());
	}

	//Simulation-context override: some simulation code legitimately runs on the
	//render thread — sprite animation callbacks (attack/zap/operate completions)
	//resolve combat while the actor thread is parked waiting for them. Those
	//sections must draw from the deterministic generator even though the render
	//thread is not a registered sim thread. Depth-counted so nesting is safe.
	private static final ThreadLocal<Integer> simContextDepth = ThreadLocal.withInitial(() -> 0);

	public static void enterSimContext(){
		simContextDepth.set(simContextDepth.get() + 1);
	}

	public static void exitSimContext(){
		int d = simContextDepth.get();
		if (d > 0) simContextDepth.set(d - 1);
	}

	//the generator the calling thread should draw from:
	//its own topmost scoped push, else the sim generator (sim threads only), else base
	private static synchronized java.util.Random current(){
		Thread t = Thread.currentThread();
		for (GenEntry entry : generators){
			if (entry.owner == t) return entry.gen;
		}
		DeterministicRandom sim = simGenerator;
		if (sim != null && (simThreads.contains(t) || simContextDepth.get() > 0)) return sim;
		return baseGenerator;
	}

	public static synchronized void resetGenerators(){
		generators = new ArrayDeque<>();
		baseGenerator = new java.util.Random();
	}

	public static synchronized void pushGenerator(){
		generators.push( new GenEntry( new java.util.Random(), Thread.currentThread() ) );
	}

	public static synchronized void pushGenerator( long seed ){
		generators.push( new GenEntry( new java.util.Random( scrambleSeed(seed) ), Thread.currentThread() ) );
	}

	//scrambles a given seed, this helps eliminate patterns between the outputs of similar seeds
	//Algorithm used is MX3 by Jon Maiga (jonkagstrom.com), CC0 license.
	private static synchronized long scrambleSeed( long seed ){
		seed ^= seed >>> 32;
		seed *= 0xbea225f9eb34556dL;
		seed ^= seed >>> 29;
		seed *= 0xbea225f9eb34556dL;
		seed ^= seed >>> 32;
		seed *= 0xbea225f9eb34556dL;
		seed ^= seed >>> 29;
		return seed;
	}

	public static synchronized void popGenerator(){
		//pop the calling thread's topmost scoped generator; the base cannot be popped
		Thread t = Thread.currentThread();
		for (java.util.Iterator<GenEntry> it = generators.iterator(); it.hasNext();){
			if (it.next().owner == t){
				it.remove();
				return;
			}
		}
		Game.reportException( new RuntimeException("tried to pop the last random number generator!"));
	}

	//returns a uniformly distributed float in the range [0, 1)
	public static synchronized float Float() {
		return Float(true);
	}

	public static synchronized float Float( boolean useGeneratorStack ) {
		if (useGeneratorStack)  return current().nextFloat();
		else                    return baseGenerator.nextFloat();
	}

	//returns a uniformly distributed float in the range [0, max)
	public static float Float( float max ) {
		return Float() * max;
	}

	//returns a uniformly distributed float in the range [min, max)
	public static float Float( float min, float max ) {
		return min + Float(max - min);
	}
	
	//returns a triangularly distributed float in the range [min, max)
	public static float NormalFloat( float min, float max ) {
		return min + ((Float(max - min) + Float(max - min))/2f);
	}

	//returns a uniformly distributed int in the range [-2^31, 2^31)
	public static synchronized int Int() {
		return Int(true);
	}

	//returns a uniformly distributed int in the range [-2^31, 2^31)
	//can either use the current generator in the stack, or force the first generator (pure random)
	public static synchronized int Int( boolean useGeneratorStack ) {
		if (useGeneratorStack)  return current().nextInt();
		else                    return baseGenerator.nextInt();
	}

	//returns a uniformly distributed int in the range [0, max)
	public static synchronized int Int( int max ) {
		return Int(max, true);
	}

	//returns a uniformly distributed int in the range [0, max)
	//can either use the current generator in the stack, or force the first generator (pure random)
	public static synchronized int Int( int max, boolean useGeneratorStack ) {
		if (max <= 0)                   return 0;
		else if (useGeneratorStack)     return current().nextInt(max);
		else                            return baseGenerator.nextInt(max);
	}

	//returns a uniformly distributed int in the range [min, max)
	public static int Int( int min, int max ) {
		return min + Int(max - min);
	}

	//returns a uniformly distributed int in the range [min, max]
	public static int IntRange( int min, int max ) {
		return min + Int(max - min + 1);
	}

	//returns a triangularly distributed int in the range [min, max]
	//this makes results more likely as they get closer to the middle of the range
	public static int NormalIntRange( int min, int max ) {
		return min + (int)((Float() + Float()) * (max - min + 1) / 2f);
	}

	//returns an inverse triangularly distributed int in the range [min, max]
	//this makes results more likely as they get further from the middle of the range
	public static int InvNormalIntRange( int min, int max ){
		float roll1 = Float(), roll2 = Float();
		if (Math.abs(roll1-0.5f) >= Math.abs(roll2-0.5f)){
			return min + (int)(roll1*(max - min + 1));
		} else {
			return min + (int)(roll2*(max - min + 1));
		}
	}

	//returns a uniformly distributed long in the range [-2^63, 2^63)
	public static synchronized long Long() {
		return Long(true);
	}

	//returns a uniformly distributed long in the range [-2^63, 2^63)
	//can either use the current generator in the stack, or force the first generator (pure random)
	public static synchronized long Long( boolean useGeneratorStack ) {
		if (useGeneratorStack)  return current().nextLong();
		else                    return baseGenerator.nextLong();
	}

	//returns a mostly uniformly distributed long in the range [0, max)
	public static long Long( long max ) {
		long result = Long();
		if (result < 0) result += Long.MAX_VALUE;
		//modulo isn't perfect, but as long as max is reasonably below 2^63 it's close enough
		return result % max;
	}

	//returns an index from chances, the probability of each index is the weight values in changes
	//negative values are treated as 0
	public static int chances( float[] chances ) {
		
		int length = chances.length;
		
		float sum = 0;
		for (int i=0; i < length; i++) {
			sum += Math.max(0, chances[i]);
		}

		if (sum <= 0){
			return -1;
		}
		
		float value = Float( sum );
		sum = 0;
		for (int i=0; i < length; i++) {
			sum += Math.max(0, chances[i]);
			if (value < sum) {
				return i;
			}
		}
		
		return -1;
	}
	
	@SuppressWarnings("unchecked")
	//returns a key element from chances, the probability of each key is the weight value it maps to
	public static <K> K chances( HashMap<K,Float> chances ) {
		
		int size = chances.size();

		Object[] values = chances.keySet().toArray();
		float[] probs = new float[size];
		float sum = 0;
		for (int i=0; i < size; i++) {
			probs[i] = chances.get( values[i] );
			sum += probs[i];
		}
		
		if (sum <= 0) {
			return null;
		}
		
		float value = Float( sum );
		
		sum = probs[0];
		for (int i=0; i < size; i++) {
			if (value < sum) {
				return (K)values[i];
			}
			sum += probs[i + 1];
		}
		
		return null;
	}
	
	public static int index( Collection<?> collection ) {
		return Int(collection.size());
	}

	@SafeVarargs
	public static<T> T oneOf(T... array ) {
		return array[Int(array.length)];
	}
	
	public static<T> T element( T[] array ) {
		return element( array, array.length );
	}
	
	public static<T> T element( T[] array, int max ) {
		return array[Int(max)];
	}
	
	@SuppressWarnings("unchecked")
	public static<T> T element( Collection<? extends T> collection ) {
		int size = collection.size();
		return size > 0 ?
			(T)collection.toArray()[Int( size )] :
			null;
	}

	public synchronized static<T> void shuffle( List<?extends T> list){
		Collections.shuffle(list, current());
	}

	public static void shuffle( int[] array ) {
		for (int i=0; i < array.length - 1; i++) {
			int j = Int( i, array.length );
			if (j != i) {
				int t = array[i];
				array[i] = array[j];
				array[j] = t;
			}
		}
	}

	public static<T> void shuffle( T[] array ) {
		for (int i=0; i < array.length - 1; i++) {
			int j = Int( i, array.length );
			if (j != i) {
				T t = array[i];
				array[i] = array[j];
				array[j] = t;
			}
		}
	}
	
	public static<U,V> void shuffle( U[] u, V[]v ) {
		for (int i=0; i < u.length - 1; i++) {
			int j = Int( i, u.length );
			if (j != i) {
				U ut = u[i];
				u[i] = u[j];
				u[j] = ut;

				V vt = v[i];
				v[i] = v[j];
				v[j] = vt;
			}
		}
	}

	// Cosmetic helpers — draw from the base (non-seeded) generator so they never consume
	// from the deterministic simulation generator. Safe to call from actor-thread code
	// (sound pitch, particle variation, etc.) without disturbing game-logic RNG state.
	public static float cosmeticFloat() {
		return Float(false);
	}
	public static float cosmeticFloat(float max) {
		return Float(false) * max;
	}
	public static float cosmeticFloat(float min, float max) {
		return min + Float(false) * (max - min);
	}
	public static int cosmeticInt(int max) {
		return Int(max, false);
	}
}
